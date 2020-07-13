package edu.harvard.seas.pl.formulog.eval;

/*-
 * #%L
 * Formulog
 * %%
 * Copyright (C) 2018 - 2020 President and Fellows of Harvard College
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import edu.harvard.seas.pl.formulog.Configuration;
import edu.harvard.seas.pl.formulog.PushPopNaiveSolver;
import edu.harvard.seas.pl.formulog.ast.*;
import edu.harvard.seas.pl.formulog.db.IndexedFactDbBuilder;
import edu.harvard.seas.pl.formulog.db.SortedIndexedFactDb;
import edu.harvard.seas.pl.formulog.magic.MagicSetTransformer;
import edu.harvard.seas.pl.formulog.smt.*;
import edu.harvard.seas.pl.formulog.symbols.RelationSymbol;
import edu.harvard.seas.pl.formulog.symbols.Symbol;
import edu.harvard.seas.pl.formulog.symbols.SymbolManager;
import edu.harvard.seas.pl.formulog.types.WellTypedProgram;
import edu.harvard.seas.pl.formulog.unification.SimpleSubstitution;
import edu.harvard.seas.pl.formulog.util.*;
import edu.harvard.seas.pl.formulog.validating.FunctionDefValidation;
import edu.harvard.seas.pl.formulog.validating.InvalidProgramException;
import edu.harvard.seas.pl.formulog.validating.ValidRule;
import edu.harvard.seas.pl.formulog.validating.ast.*;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AllDirectedPaths;
import org.jgrapht.graph.DefaultDirectedGraph;

import java.util.*;
import java.util.function.BiFunction;

public class BalbinEvaluation implements Evaluation {

    private final SortedIndexedFactDb db;
    private final SortedIndexedFactDb deltaDb;
    private final SortedIndexedFactDb nextDeltaDb;
    private final UserPredicate q;
    private final UserPredicate qInputAtom;
    private final Set<BasicRule> qMagicFacts;
    private final CountingFJP exec;
    private final Set<RelationSymbol> trackedRelations;
    private final WellTypedProgram inputProgram;
    private final Map<RelationSymbol, Set<IndexedRule>> rules;

    static final boolean sequential = System.getProperty("sequential") != null;

    @SuppressWarnings("serial")
    public static BalbinEvaluation setup(WellTypedProgram prog, int parallelism)
            throws InvalidProgramException {
        FunctionDefValidation.validate(prog);
        MagicSetTransformer mst = new MagicSetTransformer(prog);
        BasicProgram magicProg = mst.transform(Configuration.useDemandTransformation,
                MagicSetTransformer.RestoreStratification.FALSE_AND_NO_MAGIC_RULES_FOR_NEG_LITERALS);
        Set<RelationSymbol> allRelations = new HashSet<>(magicProg.getFactSymbols());
        allRelations.addAll(magicProg.getRuleSymbols());
        SortedIndexedFactDb.SortedIndexedFactDbBuilder dbb = new SortedIndexedFactDb.SortedIndexedFactDbBuilder(allRelations);
        SortedIndexedFactDb.SortedIndexedFactDbBuilder deltaDbb = new SortedIndexedFactDb.SortedIndexedFactDbBuilder(magicProg.getRuleSymbols());
        PredicateFunctionSetter predFuncs = new PredicateFunctionSetter(
                magicProg.getFunctionCallFactory().getDefManager(), dbb);

        Map<RelationSymbol, Set<IndexedRule>> rules = new HashMap<>();

        // Get query newQ created for magicProg by MagicSetTransformer
        UserPredicate newQ = magicProg.getQuery();
        RelationSymbol newQSymbol = newQ.getSymbol();
        Set<BasicRule> newQRules = magicProg.getRules(newQSymbol);

        // Get query q
        UserPredicate q = null;
        for (BasicRule basicRule : newQRules) {
            ComplexLiteral head = basicRule.getHead();
            UserPredicate headPred = getUserPredicate(head);
            RelationSymbol headPredSymbol = headPred.getSymbol();
            for (int i = 0; i < basicRule.getBodySize(); i++) {
                ComplexLiteral l = basicRule.getBody(i);
                q = getUserPredicate(l);
                if (q != null) {
                    break;
                }
            }
        }
        assert q != null : "Balbin evaluation: Could not get query";

        // Get set of magic facts for q
        UserPredicate qInputAtom = MagicSetTransformer.createInputAtom(q);
        RelationSymbol qSymbol = qInputAtom.getSymbol();
        Set<BasicRule> qRules = magicProg.getRules(qSymbol);
        Set<BasicRule> qMagicFacts = null;
        UnificationPredicate magicFactMatch = UnificationPredicate.make(Var.make("true"), Var.make("true"), false);
        for (BasicRule basicRule : qRules) {
            // Determine whether basicRule is a magic fact
            UnificationPredicate unificationPredicate = null;
            for (int i = 0; i < basicRule.getBodySize(); i++) {
                unificationPredicate = getUnificationPredicate(basicRule.getBody(i));
                if (unificationPredicate.equals(magicFactMatch)) {
                    qMagicFacts.add(basicRule);
                    break;
                }
            }
        }

        // Set up dbb and deltaDbb; add to rules
        Set<RelationSymbol> ruleSymbols = magicProg.getRuleSymbols();
        for (RelationSymbol sym : ruleSymbols) {
            Set<IndexedRule> rs = new HashSet<>();
            for (BasicRule br : magicProg.getRules(sym)) {
                for (SemiNaiveRule snr : SemiNaiveRule.make(br, ruleSymbols)) {
                    BiFunction<ComplexLiteral, Set<Var>, Integer> score = chooseScoringFunction();
                    ValidRule vr = ValidRule.make(snr, score);
                    predFuncs.preprocess(vr);
                    SimpleRule sr = SimpleRule.make(vr);
                    IndexedRule ir = IndexedRule.make(sr, p -> {
                        RelationSymbol psym = p.getSymbol();
                        if (psym instanceof SemiNaiveRule.DeltaSymbol) {
                            psym = ((SemiNaiveRule.DeltaSymbol) psym).getBaseSymbol();
                            return deltaDbb.makeIndex(psym, p.getBindingPattern());
                        } else {
                            return dbb.makeIndex(psym, p.getBindingPattern());
                        }
                    });
                    rs.add(ir);
                    if (Configuration.printFinalRules) {
                        System.err.println("[FINAL RULE]:\n" + ir);
                    }
                }
            }
            rules.put(sym, rs);
        }
        SortedIndexedFactDb db = dbb.build();
        predFuncs.setDb(db);

        SmtLibSolver smt = getSmtManager();
        try {
            smt.start(magicProg);
        } catch (EvaluationException e) {
            throw new InvalidProgramException("Problem initializing SMT shims: " + e.getMessage());
        }
        prog.getFunctionCallFactory().getDefManager().loadBuiltInFunctions(smt);

        CountingFJP exec;
        if (sequential) {
            exec = new MockCountingFJP();
        } else {
            exec = new CountingFJPImpl(parallelism);
        }

        for (RelationSymbol sym : magicProg.getFactSymbols()) {
            for (Iterable<Term[]> tups : Util.splitIterable(magicProg.getFacts(sym), Configuration.taskSize)) {
                exec.externallyAddTask(new AbstractFJPTask(exec) {

                    @Override
                    public void doTask() throws EvaluationException {
                        for (Term[] tup : tups) {
                            try {
                                db.add(sym, Terms.normalize(tup, new SimpleSubstitution()));
                            } catch (EvaluationException e) {
                                UserPredicate p = UserPredicate.make(sym, tup, false);
                                throw new EvaluationException("Cannot normalize fact " + p + ":\n" + e.getMessage());
                            }
                        }
                    }

                });
            }
        }
        exec.blockUntilFinished();
        if (exec.hasFailed()) {
            exec.shutdown();
            throw new InvalidProgramException(exec.getFailureCause());
        }
        return new BalbinEvaluation(prog, db, deltaDbb, rules, q, qInputAtom, qMagicFacts, exec,
                getTrackedRelations(magicProg.getSymbolManager()));
    }

    private static SmtLibSolver maybeDoubleCheckSolver(SmtLibSolver inner) {
        if (Configuration.smtDoubleCheckUnknowns) {
            return new DoubleCheckingSolver(inner);
        }
        return inner;
    }

    private static SmtLibSolver makeNaiveSolver() {
        return Configuration.smtUseSingleShotSolver ? new SingleShotSolver() : new CallAndResetSolver();
    }

    private static SmtLibSolver getSmtManager() {
        SmtStrategy strategy = Configuration.smtStrategy;
        switch (strategy.getTag()) {
            case QUEUE: {
                int size = (int) strategy.getMetadata();
                return new QueueSmtManager(size, () -> maybeDoubleCheckSolver(new CheckSatAssumingSolver()));
            }
            case NAIVE:
                return maybeDoubleCheckSolver(makeNaiveSolver());
            case PUSH_POP:
                return new PushPopSolver();
            case PUSH_POP_NAIVE:
                return new PushPopNaiveSolver();
            case BEST_MATCH: {
                int size = (int) strategy.getMetadata();
                return maybeDoubleCheckSolver(new BestMatchSmtManager(size));
            }
            case PER_THREAD_QUEUE: {
                int size = (int) strategy.getMetadata();
                return new PerThreadSmtManager(() -> new NotThreadSafeQueueSmtManager(size,
                        () -> maybeDoubleCheckSolver(new CheckSatAssumingSolver())));
            }
            case PER_THREAD_BEST_MATCH: {
                int size = (int) strategy.getMetadata();
                return new PerThreadSmtManager(() -> maybeDoubleCheckSolver(new BestMatchSmtManager(size)));
            }
            case PER_THREAD_PUSH_POP: {
                return new PerThreadSmtManager(() -> new PushPopSolver());
            }
            case PER_THREAD_PUSH_POP_NAIVE: {
                return new PerThreadSmtManager(() -> new PushPopNaiveSolver());
            }
            case PER_THREAD_NAIVE: {
                return new PerThreadSmtManager(() -> maybeDoubleCheckSolver(
                        Configuration.smtUseSingleShotSolver ? new SingleShotSolver() : new CallAndResetSolver()));
            }
            default:
                throw new UnsupportedOperationException("Cannot support SMT strategy: " + strategy);
        }
    }

    static Set<RelationSymbol> getTrackedRelations(SymbolManager sm) {
        Set<RelationSymbol> s = new HashSet<>();
        for (String name : Configuration.trackedRelations) {
            if (sm.hasName(name)) {
                Symbol sym = sm.lookupSymbol(name);
                if (sym instanceof RelationSymbol) {
                    s.add((RelationSymbol) sm.lookupSymbol(name));
                } else {
                    System.err.println("[WARNING] Cannot track non-relation " + sym);
                }

            } else {
                System.err.println("[WARNING] Cannot track unrecognized relation " + name);
            }
        }
        return s;
    }

    static BiFunction<ComplexLiteral, Set<Var>, Integer> chooseScoringFunction() {
        return BalbinEvaluation::score0;
    }

    static int score0(ComplexLiteral l, Set<Var> boundVars) {
        return 0;
    }

    BalbinEvaluation(WellTypedProgram inputProgram, SortedIndexedFactDb db,
                     IndexedFactDbBuilder<SortedIndexedFactDb> deltaDbb, Map<RelationSymbol, Set<IndexedRule>> rules,
                     UserPredicate q, UserPredicate qInputAtom, Set<BasicRule> qMagicFacts, CountingFJP exec,
                     Set<RelationSymbol> trackedRelations) {
        this.inputProgram = inputProgram;
        this.db = db;
        this.q = q;
        this.qInputAtom = qInputAtom;
        this.qMagicFacts = qMagicFacts;
        this.exec = exec;
        this.trackedRelations = trackedRelations;
        this.deltaDb = deltaDbb.build();
        this.nextDeltaDb = deltaDbb.build();
        this.rules = rules;
    }

    @Override
    public WellTypedProgram getInputProgram() {
        return inputProgram;
    }

    @Override
    public synchronized void run() throws EvaluationException {
        if (Configuration.printRelSizes) {
            Runtime.getRuntime().addShutdownHook(new Thread() {

                @Override
                public void run() {
                    Configuration.printRelSizes(System.err, "REL SIZE", db, true);
                }

            });
        }
        if (Configuration.debugParallelism) {
            Runtime.getRuntime().addShutdownHook(new Thread() {

                @Override
                public void run() {
                    System.err.println("[STEAL COUNT] " + exec.getStealCount());
                }

            });
        }
        eval();
    }

    // Todo: Balbin, Algorithm 7 - eval
    private void eval() throws EvaluationException {
        List<IndexedRule> l = new ArrayList<>();
        l.addAll(getPRules(qInputAtom, rules));

        // Create new BalbinEvaluationContext
//        new BalbinEvaluationContext(db, deltaDb, nextDeltaDb, qInputAtom, l, exec, trackedRelations).evaluate();
    }

    // Balbin, Definition 29 - prules (with BasicRule)
    private static Set<BasicRule> getPRules(UserPredicate q, Set<BasicRule> db) {
        RelationSymbol qSymbol = q.getSymbol();
        Set<BasicRule> prules = new HashSet<>();

        // DefaultDirectedGraph; assuming no recursive negation
        Graph<RelationSymbol, EdgeType> g = new DefaultDirectedGraph<>(EdgeType.class);
        g.addVertex(qSymbol);

        for (BasicRule basicRule : db) {
            // Case 1 - q = pred(p0), where p0 is the head of a rule
            ComplexLiteral head = basicRule.getHead();
            UserPredicate headPred = getUserPredicate(head);
            RelationSymbol headPredSymbol = headPred.getSymbol();
            if (qSymbol.equals(headPredSymbol)) {
                prules.add(basicRule);
            }

            // Construct dependency graph g for Case 2
            g.addVertex(headPredSymbol);
            for (int i = 0; i < basicRule.getBodySize(); i++) {
                UserPredicate bodyIPred = getUserPredicate(basicRule.getBody(i));
                RelationSymbol bodyIPredSymbol = bodyIPred.getSymbol();
                g.addVertex(bodyIPredSymbol);

                EdgeType edgeType = bodyIPred.isNegated() ? EdgeType.NEGATIVE : EdgeType.POSITIVE;
                g.addEdge(bodyIPredSymbol, headPredSymbol, edgeType);
            }
        }

        // Case 2 - q <--(+) pred(p0), where p0 is the head of a rule
        AllDirectedPaths<RelationSymbol, EdgeType> allPathsG = new AllDirectedPaths<RelationSymbol, EdgeType>(g);
        List<GraphPath<RelationSymbol, EdgeType>> allPaths = null;
        for (BasicRule basicRule : db) {
            ComplexLiteral head = basicRule.getHead();
            UserPredicate headPred = getUserPredicate(head);
            RelationSymbol headPredSymbol = headPred.getSymbol();

            // Get all paths from headPredSymbol to qSymbol
            // Todo: Check that getAllPaths is working as expected (i.e. for cycles)
            allPaths = allPathsG.getAllPaths(headPredSymbol, qSymbol, false, null);

            // Only add basicRule to prules if every edge in every path of allPaths is positive
            boolean foundNegativeEdge = false;
            for (GraphPath graphPath : allPaths) {
                List<EdgeType> edgeList = graphPath.getEdgeList();
                for (EdgeType edgeType : edgeList) {
                    if (edgeType == EdgeType.NEGATIVE) {
                        foundNegativeEdge = true;
                        break;
                    }
                }
                if (foundNegativeEdge) {
                    break;
                }
            }
            if (!foundNegativeEdge) {
                prules.add(basicRule);
            }
        }
        return prules;
    }

    // - prules (with IndexedRule)
    private static Set<IndexedRule> getPRules(UserPredicate q, Map<RelationSymbol, Set<IndexedRule>> rules) {
        Set<IndexedRule> db = null;
        for (Map.Entry<RelationSymbol, Set<IndexedRule>> entry : rules.entrySet()) {
            db.addAll(entry.getValue());
        }

        RelationSymbol qSymbol = q.getSymbol();
        Set<IndexedRule> prules = new HashSet<>();

        // DefaultDirectedGraph; assuming no recursive negation
        Graph<RelationSymbol, EdgeType> g = new DefaultDirectedGraph<>(EdgeType.class);
        g.addVertex(qSymbol);

        for (IndexedRule indexedRule : db) {
            // Case 1 - q = pred(p0), where p0 is the head of a rule
            SimplePredicate headPred = indexedRule.getHead();
            RelationSymbol headPredSymbol = headPred.getSymbol();
            if (qSymbol.equals(headPredSymbol)) {
                prules.add(indexedRule);
            }

            // Construct dependency graph g for Case 2
            g.addVertex(headPredSymbol);
            for (int i = 0; i < indexedRule.getBodySize(); i++) {
                SimplePredicate bodyIPred = getSimplePredicate(indexedRule.getBody(i));
                RelationSymbol bodyIPredSymbol = bodyIPred.getSymbol();
                g.addVertex(bodyIPredSymbol);

                EdgeType edgeType = bodyIPred.isNegated() ? EdgeType.NEGATIVE : EdgeType.POSITIVE;
                g.addEdge(bodyIPredSymbol, headPredSymbol, edgeType);
            }
        }

        // Case 2 - q <--(+) pred(p0), where p0 is the head of a rule
        AllDirectedPaths<RelationSymbol, EdgeType> allPathsG = new AllDirectedPaths<RelationSymbol, EdgeType>(g);
        List<GraphPath<RelationSymbol, EdgeType>> allPaths = null;
        for (IndexedRule indexedRule : db) {
            SimplePredicate headPred = indexedRule.getHead();
            RelationSymbol headPredSymbol = headPred.getSymbol();

            // Get all paths from headPredSymbol to qSymbol
            // Todo: Check that getAllPaths is working as expected (i.e. for cycles)
            allPaths = allPathsG.getAllPaths(headPredSymbol, qSymbol, false, null);

            // Only add basicRule to prules if every edge in every path of allPaths is positive
            boolean foundNegativeEdge = false;
            for (GraphPath graphPath : allPaths) {
                List<EdgeType> edgeList = graphPath.getEdgeList();
                for (EdgeType edgeType : edgeList) {
                    if (edgeType == EdgeType.NEGATIVE) {
                        foundNegativeEdge = true;
                        break;
                    }
                }
                if (foundNegativeEdge) {
                    break;
                }
            }
            if (!foundNegativeEdge) {
                prules.add(indexedRule);
            }
        }
        return prules;
    }

    private static enum EdgeType {
        NEGATIVE,
        POSITIVE
    }

    // Balbin, Definition 1 - pred(p) (for UserPredicate)
    private static UserPredicate getUserPredicate(ComplexLiteral p) {
        return p.accept(new ComplexLiterals.ComplexLiteralVisitor<Void, UserPredicate>() {

            @Override
            public UserPredicate visit(UnificationPredicate unificationPredicate, Void input) {
                return null;
            }

            @Override
            public UserPredicate visit(UserPredicate userPredicate, Void input) {
                return userPredicate;
            }

        }, null);
    }

    // - pred(p) (for UnificationPredicate)
    private static UnificationPredicate getUnificationPredicate(ComplexLiteral p) {
        return p.accept(new ComplexLiterals.ComplexLiteralVisitor<Void, UnificationPredicate>() {

            @Override
            public UnificationPredicate visit(UnificationPredicate unificationPredicate, Void input) {
                return unificationPredicate;
            }

            @Override
            public UnificationPredicate visit(UserPredicate userPredicate, Void input) {
                return null;
            }

        }, null);
    }

    // - pred(p) (for SimplePredicate)
    private static SimplePredicate getSimplePredicate(SimpleLiteral p) {
        return p.accept(new SimpleLiteralVisitor<Void, SimplePredicate>() {

            @Override
            public SimplePredicate visit(Assignment assignment, Void input) {
                return null;
            }

            @Override
            public SimplePredicate visit(Check check, Void input) {
                return null;
            }

            @Override
            public SimplePredicate visit(Destructor destructor, Void input) {
                return null;
            }

            @Override
            public SimplePredicate visit(SimplePredicate simplePredicate, Void input) {
                return simplePredicate;
            }

        }, null);
    }

    public Set<IndexedRule> getRules(RelationSymbol sym) {
        return Collections.unmodifiableSet(rules.get(sym));
    }

    @Override
    public synchronized EvaluationResult getResult() {
        return new EvaluationResult() {

            @Override
            public Iterable<UserPredicate> getAll(RelationSymbol sym) {
                if (!db.getSymbols().contains(sym)) {
                    throw new IllegalArgumentException("Unrecognized relation symbol " + sym);
                }
                return new Iterable<UserPredicate>() {

                    @Override
                    public Iterator<UserPredicate> iterator() {
                        return new FactIterator(sym, db.getAll(sym).iterator());
                    }

                };
            }

            @Override
            public Iterable<UserPredicate> getQueryAnswer() {
                if (q == null) {
                    return null;
                }
                RelationSymbol querySym = q.getSymbol();
                return new Iterable<UserPredicate>() {

                    @Override
                    public Iterator<UserPredicate> iterator() {
                        return new FactIterator(querySym, db.getAll(querySym).iterator());
                    }

                };
            }

            @Override
            public Set<RelationSymbol> getSymbols() {
                return Collections.unmodifiableSet(db.getSymbols());
            }

        };
    }

    static class FactIterator implements Iterator<UserPredicate> {

        final RelationSymbol sym;
        final Iterator<Term[]> bindings;

        public FactIterator(RelationSymbol sym, Iterator<Term[]> bindings) {
            this.sym = sym;
            this.bindings = bindings;
        }

        @Override
        public boolean hasNext() {
            return bindings.hasNext();
        }

        @Override
        public UserPredicate next() {
            return UserPredicate.make(sym, bindings.next(), false);
        }

    }

    @Override
    public boolean hasQuery() {
        return q != null;
    }

    @Override
    public UserPredicate getQuery() {
        return q;
    }

    public SortedIndexedFactDb getDb() {
        return db;
    }

    public SortedIndexedFactDb getDeltaDb() {
        return deltaDb;
    }

}
