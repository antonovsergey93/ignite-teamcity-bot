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

package org.apache.ignite.ci.web.rest.build;

import com.google.common.collect.BiMap;
import com.google.inject.Injector;
import java.text.ParseException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import org.apache.ignite.tcbot.engine.chain.FullChainRunCtx;
import org.apache.ignite.tcbot.engine.chain.LatestRebuildMode;
import org.apache.ignite.tcbot.engine.chain.ProcessLogsMode;
import org.apache.ignite.tcbot.engine.chain.BuildChainProcessor;
import org.apache.ignite.tcbot.engine.conf.ITcBotConfig;
import org.apache.ignite.ci.tcbot.trends.MasterTrendsService;
import org.apache.ignite.ci.teamcity.ignited.buildcondition.BuildCondition;
import org.apache.ignite.ci.user.ITcBotUserCreds;
import org.apache.ignite.ci.web.CtxListener;
import org.apache.ignite.tcbot.engine.ui.DsChainUi;
import org.apache.ignite.tcbot.engine.ui.DsSummaryUi;
import org.apache.ignite.tcbot.engine.ui.UpdateInfo;
import org.apache.ignite.ci.web.model.trends.BuildStatisticsSummary;
import org.apache.ignite.ci.web.model.trends.BuildsHistory;
import org.apache.ignite.tcbot.common.exeption.ServiceUnauthorizedException;
import org.apache.ignite.tcbot.persistence.IStringCompactor;
import org.apache.ignite.tcignited.ITeamcityIgnited;
import org.apache.ignite.tcignited.ITeamcityIgnitedProvider;
import org.apache.ignite.tcignited.SyncMode;
import org.apache.ignite.tcservice.ITeamcity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Strings.isNullOrEmpty;

@Path(GetBuildTestFailures.BUILD)
@Produces(MediaType.APPLICATION_JSON)
public class GetBuildTestFailures {
    public static final String BUILD = "build";

    /** Context. */
    @Context
    private ServletContext ctx;

    /** Request. */
    @Context
    private HttpServletRequest req;

    @GET
    @Path("failures/updates")
    public UpdateInfo getTestFailsUpdates(
        @QueryParam("serverId") String srvId,
        @QueryParam("buildId") Integer buildId,
        @Nullable @QueryParam("checkAllLogs") Boolean checkAllLogs) throws ServiceUnauthorizedException {
        return new UpdateInfo().copyFrom(getBuildTestFailsNoSync(srvId, buildId, checkAllLogs));
    }

    @GET
    @Path("failures/txt")
    @Produces(MediaType.TEXT_PLAIN)
    public String getTestFailsText(
        @QueryParam("serverId") String srvId,
        @QueryParam("buildId") Integer buildId,
        @Nullable @QueryParam("checkAllLogs") Boolean checkAllLogs) throws ServiceUnauthorizedException {
        return getBuildTestFails(srvId, buildId, checkAllLogs).toString();
    }

    @GET
    @Path("failuresNoSync")
    public DsSummaryUi getBuildTestFailsNoSync(
        @QueryParam("serverId") String srvId,
        @QueryParam("buildId") Integer buildId,
        @Nullable @QueryParam("checkAllLogs") Boolean checkAllLogs) {
        return collectBuildCtxById(srvId, buildId, checkAllLogs, SyncMode.NONE);
    }

    @GET
    @Path("failures")
    @NotNull public DsSummaryUi getBuildTestFails(
        @QueryParam("serverId") String srvId,
        @QueryParam("buildId") Integer buildId,
        @Nullable @QueryParam("checkAllLogs") Boolean checkAllLogs) {
        return collectBuildCtxById(srvId, buildId, checkAllLogs, SyncMode.RELOAD_QUEUED);
    }

    @NotNull public DsSummaryUi collectBuildCtxById(@QueryParam("serverId") String srvCode,
                                                    @QueryParam("buildId") Integer buildId,
                                                    @QueryParam("checkAllLogs") @Nullable Boolean checkAllLogs, SyncMode syncMode) {
        final ITcBotUserCreds prov = ITcBotUserCreds.get(req);
        final Injector injector = CtxListener.getInjector(ctx);
        ITeamcityIgnitedProvider tcIgnitedProv = injector.getInstance(ITeamcityIgnitedProvider.class);
        final BuildChainProcessor buildChainProcessor = injector.getInstance(BuildChainProcessor.class);

        final DsSummaryUi res = new DsSummaryUi();
        final AtomicInteger runningUpdates = new AtomicInteger();

        tcIgnitedProv.checkAccess(srvCode, prov);

        ITeamcityIgnited tcIgnited = tcIgnitedProv.server(srvCode, prov);

        String failRateBranch = ITeamcity.DEFAULT;

        ProcessLogsMode procLogs = (checkAllLogs != null && checkAllLogs) ? ProcessLogsMode.ALL : ProcessLogsMode.SUITE_NOT_COMPLETE;

        FullChainRunCtx ctx = buildChainProcessor.loadFullChainContext(
            tcIgnited,
            Collections.singletonList(buildId),
            LatestRebuildMode.NONE,
            procLogs,
            false,
            failRateBranch,
            syncMode,
            null,
            null);

        DsChainUi chainStatus = new DsChainUi(srvCode, tcIgnited.serverCode(), ctx.branchName());

        int cnt = (int)ctx.getRunningUpdates().count();
        if (cnt > 0)
            runningUpdates.addAndGet(cnt);

        chainStatus.initFromContext(tcIgnited, ctx, failRateBranch, injector.getInstance(IStringCompactor.class), false, null, null, -1, null, false, false);

        res.addChainOnServer(chainStatus);

        res.postProcess(runningUpdates.get());

        return res;
    }

    /**
     * Mark builds as "valid" or "invalid".
     *
     * @param buildId Build id.
     * @param isValid Is valid.
     * @param field Field.
     * @param srvIdOpt Server code (optional)
     */
    @GET
    @Path("condition")
    public Boolean setBuildCondition(
        @QueryParam("buildId") Integer buildId,
        @QueryParam("isValid") Boolean isValid,
        @QueryParam("field") String field,
        @QueryParam("serverId") String srvIdOpt) {
        Injector injector = CtxListener.getInjector(ctx);

        String srvCode = isNullOrEmpty(srvIdOpt)
            ? injector.getInstance(ITcBotConfig.class).primaryServerCode()
            : srvIdOpt;

        if (buildId == null || isValid == null)
            return null;

        ITeamcityIgnitedProvider tcIgnitedProv = injector.getInstance(ITeamcityIgnitedProvider.class);

        ITcBotUserCreds prov = ITcBotUserCreds.get(req);

        tcIgnitedProv.checkAccess(srvCode, prov);

        ITeamcityIgnited tcIgn = tcIgnitedProv.server(srvCode, prov);

        BiMap<String, String> problemNames = BuildStatisticsSummary.fullProblemNames;

        BuildCondition buildCond =
            new BuildCondition(buildId, prov.getPrincipalId(), isValid, problemNames.getOrDefault(field, field));

        return tcIgn.setBuildCondition(buildCond);
    }

    /**
     * @param srvCode Server id.
     * @param buildType Build type.
     * @param branch Branch.
     * @param sinceDate Since date.
     * @param untilDate Until date.
     * @param skipTests Skip tests.
     */
    @GET
    @Path("trends")
    public BuildsHistory getBuildsTrends(
        @Nullable @QueryParam("server") String srvCode,
        @Nullable @QueryParam("buildType") String buildType,
        @Nullable @QueryParam("branch") String branch,
        @Nullable @QueryParam("sinceDate") String sinceDate,
        @Nullable @QueryParam("untilDate") String untilDate,
        @Nullable @QueryParam("skipTests") String skipTests)  throws ParseException {

        Injector injector = CtxListener.getInjector(ctx);
        MasterTrendsService instance = injector.getInstance(MasterTrendsService.class);


        BuildsHistory buildsHist =
            instance.getBuildTrends(srvCode, buildType, branch, sinceDate, untilDate, skipTests, ITcBotUserCreds.get(req));

        if (MasterTrendsService.DEBUG)
            System.out.println("MasterTrendsService: Responding");

        return buildsHist;
    }

}
