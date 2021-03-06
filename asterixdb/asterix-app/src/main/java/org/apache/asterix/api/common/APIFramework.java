/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.api.common;

import java.io.IOException;
import java.io.PrintWriter;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import org.apache.asterix.algebra.base.ILangExpressionToPlanTranslator;
import org.apache.asterix.algebra.base.ILangExpressionToPlanTranslatorFactory;
import org.apache.asterix.api.common.Job.SubmissionMode;
import org.apache.asterix.common.config.AsterixCompilerProperties;
import org.apache.asterix.common.config.AsterixExternalProperties;
import org.apache.asterix.common.config.OptimizationConfUtil;
import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.compiler.provider.ILangCompilationProvider;
import org.apache.asterix.dataflow.data.common.AqlExpressionTypeComputer;
import org.apache.asterix.dataflow.data.common.AqlMergeAggregationExpressionFactory;
import org.apache.asterix.dataflow.data.common.AqlMissableTypeComputer;
import org.apache.asterix.dataflow.data.common.AqlPartialAggregationTypeComputer;
import org.apache.asterix.formats.base.IDataFormat;
import org.apache.asterix.jobgen.QueryLogicalExpressionJobGen;
import org.apache.asterix.lang.common.base.IAstPrintVisitorFactory;
import org.apache.asterix.lang.common.base.IQueryRewriter;
import org.apache.asterix.lang.common.base.IRewriterFactory;
import org.apache.asterix.lang.common.base.Statement;
import org.apache.asterix.lang.common.rewrites.LangRewritingContext;
import org.apache.asterix.lang.common.statement.FunctionDecl;
import org.apache.asterix.lang.common.statement.Query;
import org.apache.asterix.metadata.declared.AqlMetadataProvider;
import org.apache.asterix.om.util.AsterixAppContextInfo;
import org.apache.asterix.optimizer.base.RuleCollections;
import org.apache.asterix.result.ResultUtils;
import org.apache.asterix.runtime.job.listener.JobEventListenerFactory;
import org.apache.asterix.transaction.management.service.transaction.JobIdFactory;
import org.apache.asterix.translator.CompiledStatements.ICompiledDmlStatement;
import org.apache.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.compiler.api.HeuristicCompilerFactoryBuilder;
import org.apache.hyracks.algebricks.compiler.api.ICompiler;
import org.apache.hyracks.algebricks.compiler.api.ICompilerFactory;
import org.apache.hyracks.algebricks.compiler.rewriter.rulecontrollers.SequentialFixpointRuleController;
import org.apache.hyracks.algebricks.compiler.rewriter.rulecontrollers.SequentialOnceRuleController;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalPlan;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionEvalSizeComputer;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionTypeComputer;
import org.apache.hyracks.algebricks.core.algebra.expressions.IMergeAggregationExpressionFactory;
import org.apache.hyracks.algebricks.core.algebra.expressions.IMissableTypeComputer;
import org.apache.hyracks.algebricks.core.algebra.expressions.LogicalExpressionJobGenToExpressionRuntimeProviderAdapter;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.AlgebricksAppendable;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.LogicalOperatorPrettyPrintVisitor;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.PlanPlotter;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.PlanPrettyPrinter;
import org.apache.hyracks.algebricks.core.rewriter.base.AbstractRuleController;
import org.apache.hyracks.algebricks.core.rewriter.base.AlgebricksOptimizationContext;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;
import org.apache.hyracks.algebricks.core.rewriter.base.IOptimizationContextFactory;
import org.apache.hyracks.algebricks.core.rewriter.base.PhysicalOptimizationConfig;
import org.apache.hyracks.api.client.IHyracksClientConnection;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.api.job.JobSpecification;
import org.json.JSONException;

/**
 * Provides helper methods for compilation of a query into a JobSpec and submission
 * to Hyracks through the Hyracks client interface.
 */
public class APIFramework {
    public static final String HTML_STATEMENT_SEPARATOR = "<!-- BEGIN -->";

    private final IRewriterFactory rewriterFactory;
    private final IAstPrintVisitorFactory astPrintVisitorFactory;
    private final ILangExpressionToPlanTranslatorFactory translatorFactory;

    public APIFramework(ILangCompilationProvider compilationProvider) {
        this.rewriterFactory = compilationProvider.getRewriterFactory();
        this.astPrintVisitorFactory = compilationProvider.getAstPrintVisitorFactory();
        this.translatorFactory = compilationProvider.getExpressionToPlanTranslatorFactory();
    }

    private static List<Pair<AbstractRuleController, List<IAlgebraicRewriteRule>>> buildDefaultLogicalRewrites() {
        List<Pair<AbstractRuleController, List<IAlgebraicRewriteRule>>> defaultLogicalRewrites = new ArrayList<>();
        SequentialFixpointRuleController seqCtrlNoDfs = new SequentialFixpointRuleController(false);
        SequentialFixpointRuleController seqCtrlFullDfs = new SequentialFixpointRuleController(true);
        SequentialOnceRuleController seqOnceCtrl = new SequentialOnceRuleController(true);
        defaultLogicalRewrites.add(new Pair<>(seqOnceCtrl, RuleCollections.buildInitialTranslationRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqOnceCtrl, RuleCollections.buildTypeInferenceRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqOnceCtrl, RuleCollections.buildAutogenerateIDRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqCtrlFullDfs, RuleCollections.buildNormalizationRuleCollection()));
        defaultLogicalRewrites
                .add(new Pair<>(seqCtrlNoDfs, RuleCollections.buildCondPushDownAndJoinInferenceRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqCtrlFullDfs, RuleCollections.buildLoadFieldsRuleCollection()));
        // fj
        defaultLogicalRewrites.add(new Pair<>(seqCtrlFullDfs, RuleCollections.buildFuzzyJoinRuleCollection()));
        //
        defaultLogicalRewrites.add(new Pair<>(seqCtrlFullDfs, RuleCollections.buildNormalizationRuleCollection()));
        defaultLogicalRewrites
                .add(new Pair<>(seqCtrlNoDfs, RuleCollections.buildCondPushDownAndJoinInferenceRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqCtrlFullDfs, RuleCollections.buildLoadFieldsRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqOnceCtrl, RuleCollections.buildDataExchangeRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqCtrlNoDfs, RuleCollections.buildConsolidationRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqCtrlNoDfs, RuleCollections.buildAccessMethodRuleCollection()));
        defaultLogicalRewrites.add(new Pair<>(seqCtrlNoDfs, RuleCollections.buildPlanCleanupRuleCollection()));

        //put TXnRuleCollection!
        return defaultLogicalRewrites;
    }

    private static List<Pair<AbstractRuleController, List<IAlgebraicRewriteRule>>> buildDefaultPhysicalRewrites() {
        List<Pair<AbstractRuleController, List<IAlgebraicRewriteRule>>> defaultPhysicalRewrites = new ArrayList<>();
        SequentialOnceRuleController seqOnceCtrl = new SequentialOnceRuleController(true);
        SequentialOnceRuleController seqOnceTopLevel = new SequentialOnceRuleController(false);
        defaultPhysicalRewrites
                .add(new Pair<>(seqOnceCtrl, RuleCollections.buildPhysicalRewritesAllLevelsRuleCollection()));
        defaultPhysicalRewrites
                .add(new Pair<>(seqOnceTopLevel, RuleCollections.buildPhysicalRewritesTopLevelRuleCollection()));
        defaultPhysicalRewrites.add(new Pair<>(seqOnceCtrl, RuleCollections.prepareForJobGenRuleCollection()));
        return defaultPhysicalRewrites;
    }

    private static class AqlOptimizationContextFactory implements IOptimizationContextFactory {

        public static final AqlOptimizationContextFactory INSTANCE = new AqlOptimizationContextFactory();

        private AqlOptimizationContextFactory() {
        }

        @Override
        public IOptimizationContext createOptimizationContext(int varCounter,
                IExpressionEvalSizeComputer expressionEvalSizeComputer,
                IMergeAggregationExpressionFactory mergeAggregationExpressionFactory,
                IExpressionTypeComputer expressionTypeComputer, IMissableTypeComputer missableTypeComputer,
                PhysicalOptimizationConfig physicalOptimizationConfig, AlgebricksPartitionConstraint clusterLocations) {
            return new AlgebricksOptimizationContext(varCounter, expressionEvalSizeComputer,
                    mergeAggregationExpressionFactory, expressionTypeComputer, missableTypeComputer,
                    physicalOptimizationConfig, clusterLocations);
        }
    }

    private void printPlanPrefix(SessionConfig conf, String planName) {
        if (conf.is(SessionConfig.FORMAT_HTML)) {
            conf.out().println("<h4>" + planName + ":</h4>");
            conf.out().println("<pre>");
        } else {
            conf.out().println("----------" + planName + ":");
        }
    }

    private void printPlanPostfix(SessionConfig conf) {
        if (conf.is(SessionConfig.FORMAT_HTML)) {
            conf.out().println("</pre>");
        }
    }

    public Pair<Query, Integer> reWriteQuery(List<FunctionDecl> declaredFunctions, AqlMetadataProvider metadataProvider,
            Query q, SessionConfig conf) throws AsterixException {
        if (q == null) {
            return null;
        }
        if (!conf.is(SessionConfig.FORMAT_ONLY_PHYSICAL_OPS) && conf.is(SessionConfig.OOB_EXPR_TREE)) {
            conf.out().println();
            printPlanPrefix(conf, "Expression tree");
            q.accept(astPrintVisitorFactory.createLangVisitor(conf.out()), 0);
            printPlanPostfix(conf);
        }
        IQueryRewriter rw = rewriterFactory.createQueryRewriter();
        rw.rewrite(declaredFunctions, q, metadataProvider, new LangRewritingContext(q.getVarCounter()));
        return new Pair<>(q, q.getVarCounter());
    }

    public JobSpecification compileQuery(List<FunctionDecl> declaredFunctions,
            AqlMetadataProvider queryMetadataProvider, Query rwQ, int varCounter, String outputDatasetName,
            SessionConfig conf, ICompiledDmlStatement statement)
            throws AlgebricksException, JSONException, RemoteException, ACIDException {

        if (!conf.is(SessionConfig.FORMAT_ONLY_PHYSICAL_OPS) && conf.is(SessionConfig.OOB_REWRITTEN_EXPR_TREE)) {
            conf.out().println();

            printPlanPrefix(conf, "Rewritten expression tree");
            if (rwQ != null) {
                rwQ.accept(astPrintVisitorFactory.createLangVisitor(conf.out()), 0);
            }
            printPlanPostfix(conf);
        }

        org.apache.asterix.common.transactions.JobId asterixJobId = JobIdFactory.generateJobId();
        queryMetadataProvider.setJobId(asterixJobId);
        ILangExpressionToPlanTranslator t =
                translatorFactory.createExpressionToPlanTranslator(queryMetadataProvider, varCounter);

        ILogicalPlan plan;
        // statement = null when it's a query
        if (statement == null || statement.getKind() != Statement.Kind.LOAD) {
            plan = t.translate(rwQ, outputDatasetName, statement);
        } else {
            plan = t.translateLoad(statement);
        }

        if (!conf.is(SessionConfig.FORMAT_ONLY_PHYSICAL_OPS) && conf.is(SessionConfig.OOB_LOGICAL_PLAN)) {
            conf.out().println();

            printPlanPrefix(conf, "Logical plan");
            if (rwQ != null || (statement != null && statement.getKind() == Statement.Kind.LOAD)) {
                LogicalOperatorPrettyPrintVisitor pvisitor = new LogicalOperatorPrettyPrintVisitor(conf.out());
                PlanPrettyPrinter.printPlan(plan, pvisitor, 0);
            }
            printPlanPostfix(conf);
        }

        //print the plot for the logical plan
        AsterixExternalProperties xProps = AsterixAppContextInfo.getInstance().getExternalProperties();
        Boolean plot = xProps.getIsPlottingEnabled();
        if (plot) {
            PlanPlotter.printLogicalPlan(plan);
        }

        AsterixCompilerProperties compilerProperties = AsterixAppContextInfo.getInstance().getCompilerProperties();
        int frameSize = compilerProperties.getFrameSize();
        int sortFrameLimit = (int) (compilerProperties.getSortMemorySize() / frameSize);
        int groupFrameLimit = (int) (compilerProperties.getGroupMemorySize() / frameSize);
        int joinFrameLimit = (int) (compilerProperties.getJoinMemorySize() / frameSize);
        OptimizationConfUtil.getPhysicalOptimizationConfig().setFrameSize(frameSize);
        OptimizationConfUtil.getPhysicalOptimizationConfig().setMaxFramesExternalSort(sortFrameLimit);
        OptimizationConfUtil.getPhysicalOptimizationConfig().setMaxFramesExternalGroupBy(groupFrameLimit);
        OptimizationConfUtil.getPhysicalOptimizationConfig().setMaxFramesForJoin(joinFrameLimit);

        HeuristicCompilerFactoryBuilder builder =
                new HeuristicCompilerFactoryBuilder(AqlOptimizationContextFactory.INSTANCE);
        builder.setPhysicalOptimizationConfig(OptimizationConfUtil.getPhysicalOptimizationConfig());
        builder.setLogicalRewrites(buildDefaultLogicalRewrites());
        builder.setPhysicalRewrites(buildDefaultPhysicalRewrites());
        IDataFormat format = queryMetadataProvider.getFormat();
        ICompilerFactory compilerFactory = builder.create();
        builder.setExpressionEvalSizeComputer(format.getExpressionEvalSizeComputer());
        builder.setIMergeAggregationExpressionFactory(new AqlMergeAggregationExpressionFactory());
        builder.setPartialAggregationTypeComputer(new AqlPartialAggregationTypeComputer());
        builder.setExpressionTypeComputer(AqlExpressionTypeComputer.INSTANCE);
        builder.setMissableTypeComputer(AqlMissableTypeComputer.INSTANCE);
        builder.setClusterLocations(queryMetadataProvider.getClusterLocations());

        ICompiler compiler = compilerFactory.createCompiler(plan, queryMetadataProvider, t.getVarCounter());
        if (conf.isOptimize()) {
            compiler.optimize();
            //plot optimized logical plan
            if (plot) {
                PlanPlotter.printOptimizedLogicalPlan(plan);
            }
            if (conf.is(SessionConfig.OOB_OPTIMIZED_LOGICAL_PLAN)) {
                if (conf.is(SessionConfig.FORMAT_ONLY_PHYSICAL_OPS)) {
                    // For Optimizer tests.
                    AlgebricksAppendable buffer = new AlgebricksAppendable(conf.out());
                    PlanPrettyPrinter.printPhysicalOps(plan, buffer, 0);
                } else {
                    printPlanPrefix(conf, "Optimized logical plan");
                    if (rwQ != null || (statement != null && statement.getKind() == Statement.Kind.LOAD)) {
                        LogicalOperatorPrettyPrintVisitor pvisitor = new LogicalOperatorPrettyPrintVisitor(conf.out());
                        PlanPrettyPrinter.printPlan(plan, pvisitor, 0);
                    }
                    printPlanPostfix(conf);
                }
            }
        }
        if (rwQ != null && rwQ.isExplain()) {
            try {
                LogicalOperatorPrettyPrintVisitor pvisitor = new LogicalOperatorPrettyPrintVisitor();
                PlanPrettyPrinter.printPlan(plan, pvisitor, 0);
                ResultUtils.displayResults(pvisitor.get().toString(), conf, new ResultUtils.Stats(), null);
                return null;
            } catch (IOException e) {
                throw new AlgebricksException(e);
            }
        }

        if (!conf.isGenerateJobSpec()) {
            return null;
        }

        builder.setBinaryBooleanInspectorFactory(format.getBinaryBooleanInspectorFactory());
        builder.setBinaryIntegerInspectorFactory(format.getBinaryIntegerInspectorFactory());
        builder.setComparatorFactoryProvider(format.getBinaryComparatorFactoryProvider());
        builder.setExpressionRuntimeProvider(
                new LogicalExpressionJobGenToExpressionRuntimeProviderAdapter(QueryLogicalExpressionJobGen.INSTANCE));
        builder.setHashFunctionFactoryProvider(format.getBinaryHashFunctionFactoryProvider());
        builder.setHashFunctionFamilyProvider(format.getBinaryHashFunctionFamilyProvider());
        builder.setMissingWriterFactory(format.getMissingWriterFactory());
        builder.setPredicateEvaluatorFactoryProvider(format.getPredicateEvaluatorFactoryProvider());

        switch (conf.fmt()) {
            case LOSSLESS_JSON:
                builder.setPrinterProvider(format.getLosslessJSONPrinterFactoryProvider());
                break;
            case CSV:
                builder.setPrinterProvider(format.getCSVPrinterFactoryProvider());
                break;
            case ADM:
                builder.setPrinterProvider(format.getADMPrinterFactoryProvider());
                break;
            case CLEAN_JSON:
                builder.setPrinterProvider(format.getCleanJSONPrinterFactoryProvider());
                break;
            default:
                throw new RuntimeException("Unexpected OutputFormat!");
        }

        builder.setSerializerDeserializerProvider(format.getSerdeProvider());
        builder.setTypeTraitProvider(format.getTypeTraitProvider());
        builder.setNormalizedKeyComputerFactoryProvider(format.getNormalizedKeyComputerFactoryProvider());

        JobEventListenerFactory jobEventListenerFactory =
                new JobEventListenerFactory(asterixJobId, queryMetadataProvider.isWriteTransaction());
        JobSpecification spec = compiler.createJob(AsterixAppContextInfo.getInstance(), jobEventListenerFactory);

        if (conf.is(SessionConfig.OOB_HYRACKS_JOB)) {
            printPlanPrefix(conf, "Hyracks job");
            if (rwQ != null) {
                conf.out().println(spec.toJSON().toString(1));
                conf.out().println(spec.getUserConstraints());
            }
            printPlanPostfix(conf);
        }
        return spec;
    }

    public void executeJobArray(IHyracksClientConnection hcc, JobSpecification[] specs, PrintWriter out)
            throws Exception {
        for (JobSpecification spec : specs) {
            spec.setMaxReattempts(0);
            JobId jobId = hcc.startJob(spec);
            long startTime = System.currentTimeMillis();
            hcc.waitForCompletion(jobId);
            long endTime = System.currentTimeMillis();
            double duration = (endTime - startTime) / 1000.00;
            out.println("<pre>Duration: " + duration + " sec</pre>");
        }

    }

    public void executeJobArray(IHyracksClientConnection hcc, Job[] jobs, PrintWriter out) throws Exception {
        for (Job job : jobs) {
            job.getJobSpec().setMaxReattempts(0);
            long startTime = System.currentTimeMillis();
            try {
                JobId jobId = hcc.startJob(job.getJobSpec());
                if (job.getSubmissionMode() == SubmissionMode.ASYNCHRONOUS) {
                    continue;
                }
                hcc.waitForCompletion(jobId);
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
            long endTime = System.currentTimeMillis();
            double duration = (endTime - startTime) / 1000.00;
            out.println("<pre>Duration: " + duration + " sec</pre>");
        }
    }
}
