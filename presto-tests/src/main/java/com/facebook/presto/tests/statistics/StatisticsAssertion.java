/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.presto.tests.statistics;

import com.facebook.presto.Session;
import com.facebook.presto.cost.CachingStatsProvider;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.cost.StatsProvider;
import com.facebook.presto.execution.StageInfo;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.tests.DistributedQueryRunner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.TreeTraverser;
import org.intellij.lang.annotations.Language;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.facebook.presto.tests.statistics.MetricComparator.createMetricComparisons;
import static com.facebook.presto.tests.statistics.MetricComparison.Result.MATCH;
import static com.facebook.presto.tests.statistics.MetricComparison.Result.NO_BASELINE;
import static com.facebook.presto.tests.statistics.MetricComparison.Result.NO_ESTIMATE;
import static com.facebook.presto.tests.statistics.MetricComparisonStrategies.defaultTolerance;
import static com.facebook.presto.transaction.TransactionBuilder.transaction;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.assertTrue;

public class StatisticsAssertion
        implements AutoCloseable
{
    private final DistributedQueryRunner runner;

    public StatisticsAssertion(DistributedQueryRunner runner)
    {
        this.runner = requireNonNull(runner, "runner is null");
    }

    @Override
    public void close()
    {
        runner.close();
    }

    public Result check(@Language("SQL") String query)
    {
        return transaction(runner.getTransactionManager(), runner.getAccessControl())
                .singleStatement()
                .execute(runner.getDefaultSession(), (Session session) -> new Result(metricComparisons(session, query)));
    }

    private List<MetricComparison> metricComparisons(Session session, @Language("SQL") String query)
    {
        String queryId = runner.executeWithQueryId(session, query).getQueryId();
        Plan queryPlan = runner.getQueryPlan(new QueryId(queryId));
        StageInfo stageInfo = runner.getQueryInfo(new QueryId(queryId)).getOutputStage().get();

        StatsProvider statsProvider = new CachingStatsProvider(runner.getStatsCalculator(), session, queryPlan.getTypes());
        ImmutableMap<PlanNodeId, PlanNodeStatsEstimate> estimates = TreeTraverser.using(PlanNode::getSources)
                .preOrderTraversal(queryPlan.getRoot())
                .stream()
                .collect(toImmutableMap(PlanNode::getId, statsProvider::getStats));
        return createMetricComparisons(queryPlan, estimates, stageInfo);
    }

    public static class Result
    {
        private final Map<Metric, MetricComparison> outputNodeMetricComparisons;

        public Result(List<MetricComparison> metricComparisons)
        {
            requireNonNull(metricComparisons, "metricComparisons can not be null");
            this.outputNodeMetricComparisons = metricComparisons.stream()
                    .filter(metricComparison -> metricComparison.getPlanNode() instanceof OutputNode)
                    .collect(toImmutableMap(MetricComparison::getMetric, metricComparison -> metricComparison));
            checkArgument(outputNodeMetricComparisons.size() == Metric.values().length, "Expected all metrics for OutputNode");
        }

        public Result estimate(Metric metric, MetricComparisonStrategy strategy)
        {
            return testMetrics(metric, metricComparison -> metricComparison.result(strategy) == MATCH);
        }

        public Result noEstimate(Metric metric)
        {
            return testMetrics(metric, metricComparison -> metricComparison.result(defaultTolerance()) == NO_ESTIMATE);
        }

        public Result noBaseline(Metric metric)
        {
            return testMetrics(metric, metricComparison -> metricComparison.result(defaultTolerance()) == NO_BASELINE);
        }

        private Result testMetrics(Metric metric, Predicate<MetricComparison> assertCondition)
        {
            MetricComparison metricComparison = outputNodeMetricComparisons.get(metric);
            assertTrue(assertCondition.test(metricComparison), "Following metrics do not match: " + metricComparison);
            return this;
        }
    }
}
