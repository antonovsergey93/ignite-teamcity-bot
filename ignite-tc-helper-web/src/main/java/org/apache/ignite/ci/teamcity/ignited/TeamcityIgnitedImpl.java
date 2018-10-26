/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.ci.teamcity.ignited;


import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Sets;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import org.apache.ignite.ci.ITeamcity;
import org.apache.ignite.ci.di.AutoProfiling;
import org.apache.ignite.ci.di.MonitoredTask;
import org.apache.ignite.ci.di.scheduler.IScheduler;
import org.apache.ignite.ci.tcbot.condition.BuildCondition;
import org.apache.ignite.ci.tcbot.condition.BuildConditionDao;
import org.apache.ignite.ci.tcmodel.hist.BuildRef;
import org.apache.ignite.ci.tcmodel.result.Build;
import org.apache.ignite.ci.tcmodel.result.tests.TestOccurrencesFull;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.FatBuildCompacted;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.FatBuildDao;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.TestCompacted;
import org.apache.ignite.ci.teamcity.pure.ITeamcityConn;
import org.apache.ignite.ci.util.ExceptionUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeamcityIgnitedImpl implements ITeamcityIgnited {
    /** Logger. */
    private static final Logger logger = LoggerFactory.getLogger(TestCompacted.class);

    /** Max build id diff to enforce reload during incremental refresh. */
    public static final int MAX_ID_DIFF_TO_ENFORCE_CONTINUE_SCAN = 3000;
    public static final int FAT_BUILD_PROACTIVE_TASKS = 4;

    /** Server id. */
    private String srvId;

    /** Pure HTTP Connection API. */
    private ITeamcityConn conn;

    /** Scheduler. */
    @Inject private IScheduler scheduler;

    /** Build reference DAO. */
    @Inject private BuildRefDao buildRefDao;

    /** Build condition DAO. */
    @Inject private BuildConditionDao buildConditionDao;

    /** Build DAO. */
    @Inject private FatBuildDao fatBuildDao;

    @Inject private IStringCompactor compactor;

    /** Server ID mask for cache Entries. */
    private int srvIdMaskHigh;

    @GuardedBy("this")
    private Set<Integer> buildToLoad = new HashSet<>();

    public void init(String srvId, ITeamcityConn conn) {
        this.srvId = srvId;
        this.conn = conn;

        srvIdMaskHigh = ITeamcityIgnited.serverIdToInt(srvId);
        buildRefDao.init(); //todo init somehow in auto
        buildConditionDao.init();
        fatBuildDao.init();
    }

    /**
     * Invoke load fat builds later, re-load provided builds.
     *
     * @param buildsToAskFromTc Builds to ask from tc.
     */
    public void scheduleBuildsLoad(Collection<Integer> buildsToAskFromTc) {
        if (buildsToAskFromTc.isEmpty())
            return;

        synchronized (this) {
            buildToLoad.addAll(buildsToAskFromTc);
        }

        int ldrToActivate = ThreadLocalRandom.current().nextInt(FAT_BUILD_PROACTIVE_TASKS);

        scheduler.sheduleNamed(taskName("loadFatBuilds" + ldrToActivate), () -> loadFatBuilds(ldrToActivate), 2, TimeUnit.MINUTES);

    }

    @NotNull public String taskName(String taskName) {
        return ITeamcityIgnited.class.getSimpleName() +"." + taskName + "." + srvId;
    }

    /** {@inheritDoc} */
    @Override public String host() {
        return conn.host();
    }

    /** {@inheritDoc} */
    @AutoProfiling
    @Override public List<BuildRef> getBuildHistory(
        @Nullable String buildTypeId,
        @Nullable String branchName) {
        scheduler.sheduleNamed(taskName("actualizeRecentBuilds"), this::actualizeRecentBuilds, 2, TimeUnit.MINUTES);

        String bracnhNameQry ;
        if (ITeamcity.DEFAULT.equals(branchName))
            bracnhNameQry = "refs/heads/master";
        else
            bracnhNameQry = branchName;

        return buildRefDao.findBuildsInHistory(srvIdMaskHigh, buildTypeId, bracnhNameQry);
    }

    /** {@inheritDoc} */
    @Override public Build triggerBuild(String buildTypeId, String branchName, boolean cleanRebuild, boolean queueAtTop) {
        Build build = conn.triggerBuild(buildTypeId, branchName, cleanRebuild, queueAtTop);

        //todo may add additional parameter: load builds into DB in sync/async fashion
        runActualizeBuilds(srvId, false, Sets.newHashSet(build.getId()));

        return build;
    }

    /** {@inheritDoc} */
    @Override public boolean buildIsValid(int buildId) {
        BuildCondition cond = buildConditionDao.getBuildCondition(srvIdMaskHigh, buildId);

        return cond == null || cond.isValid;
    }

    /** {@inheritDoc} */
    @Override public boolean setBuildCondition(BuildCondition cond) {
        return buildConditionDao.setBuildCondition(srvIdMaskHigh, cond);
    }

    @Override public FatBuildCompacted getFatBuild(int buildId) {
        FatBuildCompacted existingBuild = fatBuildDao.getFatBuild(srvIdMaskHigh, buildId);

        //todo additionally check queued and running builds, refesh builds if they are queued.
        if (existingBuild != null && !existingBuild.isOutdatedEntityVersion())
            return existingBuild;

        FatBuildCompacted savedVer = reloadBuild(buildId, existingBuild);

        //build was modified, probably we need also to update reference accordindly
        if (savedVer != null)
            buildRefDao.save(srvIdMaskHigh, new BuildRefCompacted(savedVer));

        return savedVer == null ? existingBuild : savedVer;
    }

    /**
     * @param buildId
     * @param existingBuild
     * @return new build if it was updated or null if no updates detected
     */
    public FatBuildCompacted reloadBuild(int buildId, @Nullable FatBuildCompacted existingBuild) {
        //  System.err.println(Thread.currentThread().getName()+ ": Build " + buildId);
        //todo some sort of locking to avoid double requests
        Build build;
        List<TestOccurrencesFull> tests = new ArrayList<>();
        try {
            build = conn.getBuild(buildId);

            String nextHref = null;
            do {
                boolean testDtls = !build.isComposite(); // don't query test details for compoite
                TestOccurrencesFull page = conn.getTestsPage(buildId, nextHref, testDtls);
                nextHref = page.nextHref();

                tests.add(page);
            }
            while (!Strings.isNullOrEmpty(nextHref));
        }
        catch (Exception e) {
            if (Throwables.getRootCause(e) instanceof FileNotFoundException) {
                logger.info("Loading build [" + buildId + "] for server [" + srvId + "] failed:" + e.getMessage(), e);

                if (existingBuild != null) {
                    build = existingBuild.toBuild(compactor);

                    if(build.isRunning() || build.isQueued())
                        build.setCancelled();

                    tests = Collections.singletonList(existingBuild.getTestOcurrences(compactor));
                }
                else
                    build = Build.createFakeStub();
            } else {
                logger.error("Loading build [" + buildId + "] for server [" + srvId + "] failed:" + e.getMessage(), e);

                e.printStackTrace();

                throw ExceptionUtil.propagateException(e);
            }
        }

        //if we are here because of some sort of outdated version of build,
        // new save will be performed with new entity version for compacted build
        return fatBuildDao.saveBuild(srvIdMaskHigh, buildId, build, tests, existingBuild);
    }

    /**
     *
     */
    void actualizeRecentBuilds() {
        List<BuildRefCompacted> running = buildRefDao.getQueuedAndRunning(srvIdMaskHigh);

        Set<Integer> paginateUntil = new HashSet<>();
        Set<Integer> directUpload = new HashSet<>();

        List<Integer> runningIds = running.stream().map(BuildRefCompacted::id).collect(Collectors.toList());
        OptionalInt max = runningIds.stream().mapToInt(i -> i).max();
        if (max.isPresent()) {
            runningIds.forEach(id->{
                if(id > (max.getAsInt() - MAX_ID_DIFF_TO_ENFORCE_CONTINUE_SCAN))
                    paginateUntil.add(id);
                else
                    directUpload.add(id);
            });
        }
        //schedule direct reload for Fat Builds for all queued too-old builds
        scheduleBuildsLoad(directUpload);

        runActualizeBuilds(srvId, false, paginateUntil);

        if(!paginateUntil.isEmpty()) {
            //some builds may stuck in the queued or running, enforce loading as well
            scheduleBuildsLoad(paginateUntil);
        }

        // schedule full resync later
        scheduler.invokeLater(this::sheduleResync, 15, TimeUnit.MINUTES);
    }

    /**
     *
     */
    private void sheduleResync() {
        scheduler.sheduleNamed(taskName("fullReindex"), this::fullReindex, 120, TimeUnit.MINUTES);
    }

    /**
     *
     */
    void fullReindex() {
        runActualizeBuilds(srvId, true, null);
    }

    /**
     * @param srvId Server id. todo to be added as composite name extend
     * @param fullReindex Reindex all builds from TC history.
     * @param mandatoryToReload [in/out] Build ID should be found before end of sync. Ignored if fullReindex mode.
     *
     */
    @MonitoredTask(name = "Actualize BuildRefs(srv, full resync)", nameExtArgsIndexes = {0, 1})
    @AutoProfiling
    protected String runActualizeBuilds(String srvId, boolean fullReindex,
        @Nullable Set<Integer> mandatoryToReload) {
        AtomicReference<String> outLinkNext = new AtomicReference<>();
        List<BuildRef> tcDataFirstPage = conn.getBuildRefs(null, outLinkNext);

        Set<Long> buildsUpdated = buildRefDao.saveChunk(srvIdMaskHigh, tcDataFirstPage);
        int totalUpdated = buildsUpdated.size();
        scheduleBuildsLoad(cacheKeysToBuildIds(buildsUpdated));

        int totalChecked = tcDataFirstPage.size();
        int neededToFind = 0;
        if (mandatoryToReload != null) {
            neededToFind = mandatoryToReload.size();

            tcDataFirstPage.stream().map(BuildRef::getId).forEach(mandatoryToReload::remove);
        }

        while (outLinkNext.get() != null) {
            String nextPageUrl = outLinkNext.get();
            outLinkNext.set(null);
            List<BuildRef> tcDataNextPage = conn.getBuildRefs(nextPageUrl, outLinkNext);
            Set<Long> curChunkBuildsSaved = buildRefDao.saveChunk(srvIdMaskHigh, tcDataNextPage);
            totalUpdated += curChunkBuildsSaved.size();
            scheduleBuildsLoad(cacheKeysToBuildIds(curChunkBuildsSaved));

            int savedCurChunk = curChunkBuildsSaved.size();

            totalChecked += tcDataNextPage.size();

            if (!fullReindex) {
                if (mandatoryToReload!=null && !mandatoryToReload.isEmpty())
                    tcDataNextPage.stream().map(BuildRef::getId).forEach(mandatoryToReload::remove);

                if (savedCurChunk == 0 && (mandatoryToReload==null || mandatoryToReload.isEmpty()))
                    break; // There are no modification at current page, hopefully no modifications at all
            }
        }

        int leftToFind = mandatoryToReload == null ? 0 : mandatoryToReload.size();
        return "Entries saved " + totalUpdated + " Builds checked " + totalChecked + " Needed to find " + neededToFind   + " remained to find " + leftToFind;
    }

    @NotNull private List<Integer> cacheKeysToBuildIds(Collection<Long> cacheKeysUpdated) {
        return cacheKeysUpdated.stream().map(BuildRefDao::cacheKeyToBuildId).collect(Collectors.toList());
    }

    /** */
    private void loadFatBuilds(int ldrNo) {
        Set<Integer> load;

        synchronized (this) {
            load = buildToLoad;
            buildToLoad = new HashSet<>();
        }

        doLoadBuilds(ldrNo, srvId, load);
    }

    @MonitoredTask(name = "Proactive Builds Loading (agent,server)", nameExtArgsIndexes = {0, 1})
    @AutoProfiling
    protected String doLoadBuilds(int ldrNo, String srvId, Set<Integer> load) {
        if(load.isEmpty())
            return "Nothing to load";

        AtomicInteger err = new AtomicInteger();
        AtomicInteger ld = new AtomicInteger();

        Map<Long, FatBuildCompacted> builds = fatBuildDao.getAllFatBuilds(srvIdMaskHigh, load);

        load.forEach(
            buildId -> {
                try {
                    FatBuildCompacted existingBuild = builds.get(FatBuildDao.buildIdToCacheKey(srvIdMaskHigh, buildId));

                    FatBuildCompacted savedVer = reloadBuild(buildId, existingBuild);

                    if (savedVer != null)
                        ld.incrementAndGet();
                }
                catch (Exception e) {
                    logger.error("", e);
                    err.incrementAndGet();
                }
            }
        );

        return "Builds updated " + ld.get() + " from " + load.size() + " requested, errors: " + err;
    }
}
