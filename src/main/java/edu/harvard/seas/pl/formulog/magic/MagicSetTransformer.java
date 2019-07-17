package edu.harvard.seas.pl.formulog.magic;

/*-
 * #%L
 * FormuLog
 * %%
 * Copyright (C) 2018 - 2019 President and Fellows of Harvard College
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import edu.harvard.seas.pl.formulog.ast.BasicProgram;
import edu.harvard.seas.pl.formulog.ast.BasicRule;
import edu.harvard.seas.pl.formulog.ast.ComplexConjunct;
import edu.harvard.seas.pl.formulog.ast.ComplexConjuncts.ComplexConjunctVisitor;
import edu.harvard.seas.pl.formulog.ast.Constructor;
import edu.harvard.seas.pl.formulog.ast.Expr;
import edu.harvard.seas.pl.formulog.ast.Exprs.ExprVisitor;
import edu.harvard.seas.pl.formulog.ast.Exprs.ExprVisitorExn;
import edu.harvard.seas.pl.formulog.ast.FunctionCallFactory;
import edu.harvard.seas.pl.formulog.ast.FunctionCallFactory.FunctionCall;
import edu.harvard.seas.pl.formulog.ast.MatchClause;
import edu.harvard.seas.pl.formulog.ast.MatchExpr;
import edu.harvard.seas.pl.formulog.ast.Primitive;
import edu.harvard.seas.pl.formulog.ast.Program;
import edu.harvard.seas.pl.formulog.ast.Term;
import edu.harvard.seas.pl.formulog.ast.Terms;
import edu.harvard.seas.pl.formulog.ast.Terms.TermVisitor;
import edu.harvard.seas.pl.formulog.ast.Terms.TermVisitorExn;
import edu.harvard.seas.pl.formulog.ast.UnificationPredicate;
import edu.harvard.seas.pl.formulog.ast.UserPredicate;
import edu.harvard.seas.pl.formulog.ast.Var;
import edu.harvard.seas.pl.formulog.ast.functions.CustomFunctionDef;
import edu.harvard.seas.pl.formulog.ast.functions.FunctionDef;
import edu.harvard.seas.pl.formulog.eval.EvaluationException;
import edu.harvard.seas.pl.formulog.symbols.FunctionSymbol;
import edu.harvard.seas.pl.formulog.symbols.PredicateFunctionSymbolFactory.PredicateFunctionSymbol;
import edu.harvard.seas.pl.formulog.symbols.RelationSymbol;
import edu.harvard.seas.pl.formulog.symbols.Symbol;
import edu.harvard.seas.pl.formulog.symbols.SymbolManager;
import edu.harvard.seas.pl.formulog.types.FunctorType;
import edu.harvard.seas.pl.formulog.unification.SimpleSubstitution;
import edu.harvard.seas.pl.formulog.unification.Unification;
import edu.harvard.seas.pl.formulog.util.DedupWorkList;
import edu.harvard.seas.pl.formulog.util.Util;
import edu.harvard.seas.pl.formulog.validating.InvalidProgramException;
import edu.harvard.seas.pl.formulog.validating.Stratifier;
import edu.harvard.seas.pl.formulog.validating.Stratum;

public class MagicSetTransformer {

	private final Program<UserPredicate, BasicRule> origProg;
	private boolean topDownIsDefault;

	private static final boolean debug = System.getProperty("debugMst") != null;

	public MagicSetTransformer(Program<UserPredicate, BasicRule> prog) {
		this.origProg = prog;
	}

	public BasicProgram transform(boolean useDemandTransformation, boolean restoreStratification)
			throws InvalidProgramException {
		if (origProg.hasQuery()) {
			return transformForQuery(origProg.getQuery(), useDemandTransformation, restoreStratification);
		} else {
			return transformNoQuery(useDemandTransformation, restoreStratification);
		}
	}

	public BasicProgram transformForQuery(UserPredicate query, boolean useDemandTransformation,
			boolean restoreStratification) throws InvalidProgramException {
		topDownIsDefault = true;
		if (query.isNegated()) {
			throw new InvalidProgramException("Query cannot be negated");
		}
		query = reduceQuery(query);
		BasicProgram newProg;
		if (query.getSymbol().isEdbSymbol()) {
			newProg = makeEdbProgram(query);
		} else {
			UserPredicate adornedQuery = Adornments.adorn(query, Collections.emptySet(), topDownIsDefault);
			Set<BasicRule> adRules = adorn(Collections.singleton(adornedQuery.getSymbol()));
			Set<BasicRule> magicRules = makeMagicRules(adRules);
			magicRules.add(makeSeedRule(adornedQuery));
			BasicProgram magicProg = new ProgramImpl(magicRules, adornedQuery);
			if (restoreStratification && !isStratified(magicProg)) {
				magicProg = stratify(magicProg, adRules);
			}
			if (useDemandTransformation) {
				magicProg = stripAdornments(magicProg);
			}
			newProg = magicProg;
		}
		return newProg;
	}

	private static final TermVisitorExn<Void, Term, EvaluationException> termReducer = new TermVisitorExn<Void, Term, EvaluationException>() {

		@Override
		public Term visit(Var x, Void in) throws EvaluationException {
			return x;
		}

		@Override
		public Term visit(Constructor c, Void in) throws EvaluationException {
			Term[] args = Terms.mapExn(c.getArgs(), t -> t.visit(this, null));
			return c.copyWithNewArgs(args);
		}

		@Override
		public Term visit(Primitive<?> p, Void in) throws EvaluationException {
			return p;
		}

		@Override
		public Term visit(Expr e, Void in) throws EvaluationException {
			return e.visit(exprReducer, in);
		}

	};

	private static final ExprVisitorExn<Void, Term, EvaluationException> exprReducer = new ExprVisitorExn<Void, Term, EvaluationException>() {

		@Override
		public Term visit(MatchExpr matchExpr, Void in) throws EvaluationException {
			Term matchee = matchExpr.getMatchee().visit(termReducer, in);
			List<MatchClause> clauses = new ArrayList<>();
			for (MatchClause cl : matchExpr.getClauses()) {
				Term rhs = cl.getRhs().visit(termReducer, in);
				clauses.add(MatchClause.make(cl.getLhs(), rhs));
			}
			return MatchExpr.make(matchee, clauses);
		}

		@Override
		public Term visit(FunctionCall funcCall, Void in) throws EvaluationException {
			Term[] args = Terms.mapExn(funcCall.getArgs(), t -> t.visit(termReducer, null));
			return funcCall.copyWithNewArgs(args);
		}

	};

	public UserPredicate reduceQuery(UserPredicate query) throws InvalidProgramException {
		try {
			Term[] args = query.getArgs();
			Term[] newArgs = new Term[args.length];
			for (int i = 0; i < args.length; ++i) {
				newArgs[i] = args[i].visit(termReducer, null);
			}
			return UserPredicate.make(query.getSymbol(), newArgs, query.isNegated());
		} catch (EvaluationException e) {
			throw new InvalidProgramException(
					"Query contained function call that could not be normalized: " + query + "\n" + e);
		}
	}

	private BasicRule makeSeedRule(UserPredicate adornedQuery) {
		return BasicRule.make(createInputAtom(adornedQuery));
	}

	private BasicProgram makeEdbProgram(UserPredicate query) {
		RelationSymbol querySym = query.getSymbol();
		Set<Term[]> facts = new HashSet<>();
		for (Term[] fact : origProg.getFacts(querySym)) {
			try {
				if (Unification.unify(query.getArgs(), fact, new SimpleSubstitution())) {
					facts.add(fact);
				}
			} catch (EvaluationException e) {
				throw new AssertionError("impossible");
			}
		}
		return new BasicProgram() {

			@Override
			public Set<FunctionSymbol> getFunctionSymbols() {
				return Collections.emptySet();
			}

			@Override
			public Set<RelationSymbol> getFactSymbols() {
				return Collections.singleton(querySym);
			}

			@Override
			public Set<RelationSymbol> getRuleSymbols() {
				return Collections.emptySet();
			}

			@Override
			public FunctionDef getDef(FunctionSymbol sym) {
				throw new IllegalArgumentException("No definition for function with symbol: " + sym);
			}

			@Override
			public Set<Term[]> getFacts(RelationSymbol sym) {
				if (querySym.equals(sym)) {
					return facts;
				}
				return Collections.emptySet();
			}

			@Override
			public Set<BasicRule> getRules(RelationSymbol sym) {
				return Collections.emptySet();
			}

			@Override
			public SymbolManager getSymbolManager() {
				return origProg.getSymbolManager();
			}

			@Override
			public boolean hasQuery() {
				return true;
			}

			@Override
			public UserPredicate getQuery() {
				return query;
			}

			@Override
			public FunctionCallFactory getFunctionCallFactory() {
				return origProg.getFunctionCallFactory();
			}

		};
	}

	private BasicProgram transformNoQuery(boolean useDemandTransformation, boolean restoreStratification)
			throws InvalidProgramException {
		topDownIsDefault = false;
		Set<RelationSymbol> bottomUpSymbols = new HashSet<>();
		for (RelationSymbol sym : origProg.getRuleSymbols()) {
			if (!sym.isTopDown()) {
				bottomUpSymbols.add(sym);
			}
		}
		Set<BasicRule> adRules = adorn(bottomUpSymbols);
		Set<BasicRule> magicRules = makeMagicRules(adRules);
		BasicProgram magicProg = new ProgramImpl(magicRules, null);
		if (restoreStratification && !isStratified(magicProg)) {
			magicProg = stratify(magicProg, adRules);
		}
		if (useDemandTransformation) {
			magicProg = stripAdornments(magicProg);
		}
		return magicProg;
	}

	private BasicProgram stripAdornments(BasicProgram prog) throws InvalidProgramException {
		Set<BasicRule> rules = new HashSet<>();
		for (RelationSymbol sym : prog.getRuleSymbols()) {
			for (BasicRule r : prog.getRules(sym)) {
				UserPredicate newHead = stripAdornment(r.getHead());
				List<ComplexConjunct> newBody = new ArrayList<>();
				for (ComplexConjunct atom : r) {
					newBody.add(stripAdornment(atom));
				}
				rules.add(BasicRule.make(newHead, newBody));
			}
		}
		UserPredicate query = null;
		if (prog.hasQuery()) {
			query = stripAdornment(prog.getQuery());
		}
		return new ProgramImpl(rules, query);
	}

	private static <C extends ComplexConjunct> C stripAdornment(C atom) {
		return atom.accept(new ComplexConjunctVisitor<Void, C>() {

			@SuppressWarnings("unchecked")
			@Override
			public C visit(UnificationPredicate unificationPredicate, Void input) {
				return (C) unificationPredicate;
			}

			@SuppressWarnings("unchecked")
			@Override
			public C visit(UserPredicate userPredicate, Void input) {
				RelationSymbol sym = userPredicate.getSymbol();
				if (sym instanceof PositiveSymbol) {
					sym = ((PositiveSymbol) sym).getUnderlyingSymbol();
					if (sym instanceof AdornedSymbol) {
						sym = ((AdornedSymbol) sym).getSymbol();
					}
					sym = new PositiveSymbol(sym);
				} else if (sym instanceof AdornedSymbol) {
					sym = ((AdornedSymbol) sym).getSymbol();
				}
				return (C) UserPredicate.make(sym, userPredicate.getArgs(), userPredicate.isNegated());
			}

		}, null);
	}

	private Set<BasicRule> adorn(Set<RelationSymbol> seeds) throws InvalidProgramException {
		if (debug) {
			System.err.println("Adorning rules...");
		}
		Set<BasicRule> adRules = new HashSet<>();
		DedupWorkList<RelationSymbol> worklist = new DedupWorkList<>();
		for (RelationSymbol seed : seeds) {
			worklist.push(seed);
		}
		while (!worklist.isEmpty()) {
			RelationSymbol adSym = worklist.pop();
			RelationSymbol origSym = adSym;
			if (adSym instanceof AdornedSymbol) {
				origSym = ((AdornedSymbol) adSym).getSymbol();
			}
			for (BasicRule r : origProg.getRules(origSym)) {
				UserPredicate head = r.getHead();
				if (head.getSymbol().equals(origSym)) {
					UserPredicate adHead = UserPredicate.make(adSym, head.getArgs(), head.isNegated());
					BasicRule adRule = Adornments.adornRule(adHead, Util.iterableToList(r), topDownIsDefault);
					for (ComplexConjunct a : adRule) {
						a.accept(new ComplexConjunctVisitor<Void, Void>() {

							@Override
							public Void visit(UnificationPredicate unificationPredicate, Void input) {
								// Do nothing
								return null;
							}

							@Override
							public Void visit(UserPredicate userPredicate, Void input) {
								RelationSymbol sym = userPredicate.getSymbol();
								if (sym.isIdbSymbol()) {
									worklist.push(sym);
								}
								return null;
							}

						}, null);
					}
					if (debug) {
						System.err.println(adRule);
					}
					adRules.add(adRule);
				}
			}
		}
		return adRules;
	}

	private Set<BasicRule> makeMagicRules(Set<BasicRule> adornedRules) {
		Set<BasicRule> magicRules = new HashSet<>();
		int ruleNum = 0;
		for (BasicRule adornedRule : adornedRules) {
			magicRules.addAll(makeMagicRules(adornedRule, ruleNum));
			ruleNum++;
		}
		return magicRules;
	}

	private boolean exploreTopDown(RelationSymbol sym) {
		if (sym instanceof AdornedSymbol) {
			sym = ((AdornedSymbol) sym).getSymbol();
		}
		if (!sym.isIdbSymbol()) {
			return false;
		}
		return sym.isTopDown() || (topDownIsDefault && !sym.isBottomUp());
	}

	private Set<BasicRule> makeMagicRules(BasicRule r, int number) {
		int supCount[] = { 0 };
		Set<BasicRule> magicRules = new HashSet<>();
		List<Set<Var>> liveVarsByAtom = liveVarsByAtom(r);
		List<ComplexConjunct> l = new ArrayList<>();
		UserPredicate head = r.getHead();
		Set<Var> curLiveVars = new HashSet<>();
		if (exploreTopDown(head.getSymbol())) {
			UserPredicate inputAtom = createInputAtom(head);
			l.add(inputAtom);
			curLiveVars.addAll(inputAtom.varSet());
		}
		int i = 0;
		for (ComplexConjunct a : r) {
			Set<Var> futureLiveVars = liveVarsByAtom.get(i);
			Set<Var> nextLiveVars = curLiveVars.stream().filter(futureLiveVars::contains).collect(Collectors.toSet());
			a.accept(new ComplexConjunctVisitor<List<ComplexConjunct>, Void>() {

				@Override
				public Void visit(UnificationPredicate unificationPredicate, List<ComplexConjunct> l) {
					l.add(a);
					return null;
				}

				@Override
				public Void visit(UserPredicate userPredicate, List<ComplexConjunct> l) {
					RelationSymbol sym = userPredicate.getSymbol();
					if (exploreTopDown(sym)) {
						Set<Var> supVars = a.varSet().stream().filter(curLiveVars::contains).collect(Collectors.toSet());
						supVars.addAll(nextLiveVars);
						UserPredicate supAtom = createSupAtom(supVars, number, supCount[0], head.getSymbol());
						magicRules.add(BasicRule.make(supAtom, l));
						magicRules.add(BasicRule.make(createInputAtom(userPredicate), Collections.singletonList(supAtom)));
						l = new ArrayList<>();
						l.add(supAtom);
						l.add(a);
						supCount[0]++;
					} else {
						l.add(a);
					}
					return null;
				}
				
			}, null);
			curLiveVars.clear();
			curLiveVars.addAll(nextLiveVars);
			for (Var v : a.varSet()) {
				if (futureLiveVars.contains(v)) {
					curLiveVars.add(v);
				}
			}
			i++;
		}
		magicRules.add(BasicRule.make(head, l));
		return magicRules;
	}

	private List<Set<Var>> liveVarsByAtom(BasicRule r) {
		List<Set<Var>> liveVars = new ArrayList<>();
		Set<Var> acc = r.getHead().varSet();
		liveVars.add(acc);
		for (int i = r.getBodySize() - 1; i > 0; i--) {
			acc.addAll(r.getBody(i).varSet());
			liveVars.add(acc);
		}
		Collections.reverse(liveVars);
		return liveVars;
	}

	private UserPredicate createSupAtom(Set<Var> curLiveVars, int ruleNum, int supCount, Symbol headSym) {
		Term[] args = (new ArrayList<>(curLiveVars)).toArray(new Term[0]);
		SupSymbol supSym = new SupSymbol(ruleNum, supCount, args.length);
		return UserPredicate.make(supSym, args, false);
	}

	private UserPredicate createInputAtom(UserPredicate a) {
		AdornedSymbol headSym = (AdornedSymbol) a.getSymbol();
		InputSymbol inputSym = new InputSymbol(headSym);
		Term[] inputArgs = new Term[inputSym.getArity()];
		Term[] args = a.getArgs();
		boolean[] adornment = headSym.getAdornment();
		for (int i = 0, j = 0; i < args.length; i++) {
			if (adornment[i]) {
				inputArgs[j] = args[i];
				j++;
			}
		}
		return UserPredicate.make(inputSym, inputArgs, false);
	}

	public static class SupSymbol implements RelationSymbol {

		private final int ruleNum;
		private final int supCount;
		private final int arity;

		public SupSymbol(int ruleNum, int supCount, int arity) {
			this.ruleNum = ruleNum;
			this.supCount = supCount;
			this.arity = arity;
		}

		@Override
		public int getArity() {
			return arity;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + arity;
			result = prime * result + ruleNum;
			result = prime * result + supCount;
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SupSymbol other = (SupSymbol) obj;
			if (arity != other.arity)
				return false;
			if (ruleNum != other.ruleNum)
				return false;
			if (supCount != other.supCount)
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "sup_" + ruleNum + "_" + supCount;
		}

		@Override
		public FunctorType getCompileTimeType() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isIdbSymbol() {
			return true;
		}

		@Override
		public boolean isBottomUp() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isTopDown() {
			throw new UnsupportedOperationException();
		}

	}

	public static class InputSymbol implements RelationSymbol {

		private final AdornedSymbol underlyingSymbol;

		public InputSymbol(AdornedSymbol underlyingSymbol) {
			this.underlyingSymbol = underlyingSymbol;
		}

		@Override
		public int getArity() {
			int nbound = 0;
			for (boolean b : underlyingSymbol.getAdornment()) {
				nbound += b ? 1 : 0;
			}
			return nbound;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((underlyingSymbol == null) ? 0 : underlyingSymbol.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			InputSymbol other = (InputSymbol) obj;
			if (underlyingSymbol == null) {
				if (other.underlyingSymbol != null)
					return false;
			} else if (!underlyingSymbol.equals(other.underlyingSymbol))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "input_" + underlyingSymbol;
		}

		@Override
		public FunctorType getCompileTimeType() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isIdbSymbol() {
			return true;
		}

		@Override
		public boolean isBottomUp() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isTopDown() {
			throw new UnsupportedOperationException();
		}

	}

	private boolean isStratified(BasicProgram p) {
		try {
			Stratifier stratifier = new Stratifier(p);
			for (Stratum s : stratifier.stratify()) {
				if (s.hasRecursiveNegationOrAggregation()) {
					return false;
				}
			}
			return true;
		} catch (InvalidProgramException e) {
			return false;
		}
	}

	private Set<BasicRule> adjustAdornedRules(Set<BasicRule> adRules) {
		Set<BasicRule> newRules = new HashSet<>();
		for (BasicRule r : adRules) {
			UserPredicate head = r.getHead();
			if (exploreTopDown(head.getSymbol())) {
				List<ComplexConjunct> body = new ArrayList<>();
				body.add(createInputAtom(head));
				r.forEach(body::add);
				newRules.add(BasicRule.make(head, body));
			} else {
				newRules.add(r);
			}
		}
		return newRules;
	}

	private ProgramImpl stratify(BasicProgram p, Set<BasicRule> adornedRules) throws InvalidProgramException {
		Set<BasicRule> newRules = new HashSet<>();
		for (RelationSymbol sym : p.getRuleSymbols()) {
			for (BasicRule r : p.getRules(sym)) {
				UserPredicate head = makePositive(r.getHead());
				List<ComplexConjunct> body = makePositive(r);
				newRules.add(BasicRule.make(head, body));
			}
		}
		newRules.addAll(adjustAdornedRules(adornedRules));
		return new ProgramImpl(newRules, p.getQuery());
	}

	private <C extends ComplexConjunct> C makePositive(C atom) {
		return atom.accept(new ComplexConjunctVisitor<Void, C>() {

			@SuppressWarnings("unchecked")
			@Override
			public C visit(UnificationPredicate unificationPredicate, Void input) {
				return (C) unificationPredicate;
			}

			@SuppressWarnings("unchecked")
			@Override
			public C visit(UserPredicate userPredicate, Void input) {
				RelationSymbol sym = userPredicate.getSymbol();
				if (sym.isIdbSymbol() && !(sym instanceof InputSymbol || sym instanceof SupSymbol)) {
					if (userPredicate.isNegated()) {
						return null;
					}
					userPredicate = UserPredicate.make(new PositiveSymbol(sym), userPredicate.getArgs(), false);
				}
				return (C) userPredicate;
			}

		}, null);
	}

	private List<ComplexConjunct> makePositive(Iterable<ComplexConjunct> atoms) {
		List<ComplexConjunct> l = new ArrayList<>();
		for (ComplexConjunct a : atoms) {
			ComplexConjunct b = makePositive(a);
			if (b != null) {
				l.add(b);
			}
		}
		return l;
	}

	private static class PositiveSymbol implements RelationSymbol {

		private final RelationSymbol underlyingSymbol;

		public PositiveSymbol(RelationSymbol underlyingSymbol) {
			this.underlyingSymbol = underlyingSymbol;
		}

		public RelationSymbol getUnderlyingSymbol() {
			return underlyingSymbol;
		}

		@Override
		public int getArity() {
			return underlyingSymbol.getArity();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((underlyingSymbol == null) ? 0 : underlyingSymbol.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			PositiveSymbol other = (PositiveSymbol) obj;
			if (underlyingSymbol == null) {
				if (other.underlyingSymbol != null)
					return false;
			} else if (!underlyingSymbol.equals(other.underlyingSymbol))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "p_" + underlyingSymbol;
		}

		@Override
		public FunctorType getCompileTimeType() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isIdbSymbol() {
			return true;
		}

		@Override
		public boolean isBottomUp() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isTopDown() {
			throw new UnsupportedOperationException();
		}

	}

	private static class HiddenPredicateFinder {

		private final Program<UserPredicate, BasicRule> origProg;
		private final Set<FunctionSymbol> visitedFunctions = new HashSet<>();
		private final Set<RelationSymbol> seenPredicates = new HashSet<>();

		public HiddenPredicateFinder(Program<UserPredicate, BasicRule> origProg) {
			this.origProg = origProg;
		}

		public void findHiddenPredicates(Term t) {
			t.visit(predicatesInTermExtractor, seenPredicates);
		}

		public Set<RelationSymbol> getSeenPredicates() {
			return seenPredicates;
		}

		private TermVisitor<Set<RelationSymbol>, Void> predicatesInTermExtractor = new TermVisitor<Set<RelationSymbol>, Void>() {

			@Override
			public Void visit(Var t, Set<RelationSymbol> in) {
				return null;
			}

			@Override
			public Void visit(Constructor c, Set<RelationSymbol> in) {
				for (Term t : c.getArgs()) {
					t.visit(this, in);
				}
				return null;
			}

			@Override
			public Void visit(Primitive<?> p, Set<RelationSymbol> in) {
				return null;
			}

			@Override
			public Void visit(Expr e, Set<RelationSymbol> in) {
				e.visit(predicatesInExprExtractor, in);
				return null;
			}

		};

		private ExprVisitor<Set<RelationSymbol>, Void> predicatesInExprExtractor = new ExprVisitor<Set<RelationSymbol>, Void>() {

			@Override
			public Void visit(MatchExpr matchExpr, Set<RelationSymbol> in) {
				matchExpr.getMatchee().visit(predicatesInTermExtractor, in);
				for (MatchClause cl : matchExpr.getClauses()) {
					cl.getRhs().visit(predicatesInTermExtractor, in);
				}
				return null;
			}

			@Override
			public Void visit(FunctionCall funcCall, Set<RelationSymbol> in) {
				FunctionSymbol sym = funcCall.getSymbol();
				if (sym instanceof PredicateFunctionSymbol) {
					in.add(((PredicateFunctionSymbol) sym).getPredicateSymbol());
				} else if (visitedFunctions.add(sym)) {
					FunctionDef def = origProg.getDef(sym);
					if (def instanceof CustomFunctionDef) {
						((CustomFunctionDef) def).getBody().visit(predicatesInTermExtractor, in);
					}
				}
				for (Term t : funcCall.getArgs()) {
					t.visit(predicatesInTermExtractor, in);
				}
				return null;
			}

		};

	}

	private class ProgramImpl implements BasicProgram {

		private final Map<RelationSymbol, Set<BasicRule>> rules = new HashMap<>();
		private final Map<RelationSymbol, Set<Term[]>> facts = new HashMap<>();
		private final UserPredicate query;

		public ProgramImpl(Set<BasicRule> rs, UserPredicate query) throws InvalidProgramException {
			HiddenPredicateFinder hpf = new HiddenPredicateFinder(origProg);
			for (BasicRule r : rs) {
				UserPredicate head = r.getHead();
				Util.lookupOrCreate(rules, head.getSymbol(), () -> new HashSet<>()).add(r);
				for (ComplexConjunct a : r) {
					a.accept(new ComplexConjunctVisitor<Void, Void>() {

						@Override
						public Void visit(UnificationPredicate unificationPredicate, Void input) {
							hpf.findHiddenPredicates(unificationPredicate.getLhs());
							hpf.findHiddenPredicates(unificationPredicate.getRhs());
							return null;
						}

						@Override
						public Void visit(UserPredicate userPredicate, Void input) {
							RelationSymbol sym = userPredicate.getSymbol();
							if (sym.isEdbSymbol()) {
								facts.putIfAbsent(sym, origProg.getFacts(sym));
							}
							for (Term t : userPredicate.getArgs()) {
								hpf.findHiddenPredicates(t);
							}
							return null;
						}

					}, null);
				}
				for (RelationSymbol psym : hpf.getSeenPredicates()) {
					if (exploreTopDown(psym)) {
						throw new InvalidProgramException("Cannot refer to top-down IDB predicate " + psym
								+ " in a function; consider annotating " + psym + " with @bottomup");

					}
					if (psym.isEdbSymbol()) {
						facts.putIfAbsent(psym, origProg.getFacts(psym));
					}
				}
			}
			// Do not keep unnecessary facts around if there is a query.
			if (query == null) {
				for (RelationSymbol sym : origProg.getFactSymbols()) {
					facts.putIfAbsent(sym, origProg.getFacts(sym));
				}
			}
			this.query = query;
		}

		@Override
		public Set<FunctionSymbol> getFunctionSymbols() {
			return origProg.getFunctionSymbols();
		}

		@Override
		public Set<RelationSymbol> getFactSymbols() {
			return Collections.unmodifiableSet(facts.keySet());
		}

		@Override
		public Set<RelationSymbol> getRuleSymbols() {
			return Collections.unmodifiableSet(rules.keySet());
		}

		@Override
		public FunctionDef getDef(FunctionSymbol sym) {
			return origProg.getDef(sym);
		}

		@Override
		public Set<Term[]> getFacts(RelationSymbol sym) {
			assert sym.isEdbSymbol();
			return Util.lookupOrCreate(facts, sym, () -> Collections.emptySet());
		}

		@Override
		public Set<BasicRule> getRules(RelationSymbol sym) {
			assert sym.isIdbSymbol();
			return Util.lookupOrCreate(rules, sym, () -> Collections.emptySet());
		}

		@Override
		public SymbolManager getSymbolManager() {
			return origProg.getSymbolManager();
		}

		@Override
		public boolean hasQuery() {
			return query != null;
		}

		@Override
		public UserPredicate getQuery() {
			return query;
		}

		@Override
		public FunctionCallFactory getFunctionCallFactory() {
			return origProg.getFunctionCallFactory();
		}

	}

}
