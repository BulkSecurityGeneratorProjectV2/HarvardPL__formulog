package edu.harvard.seas.pl.formulog.eval;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.harvard.seas.pl.formulog.ast.BasicProgram;
import edu.harvard.seas.pl.formulog.ast.BasicRule;
import edu.harvard.seas.pl.formulog.ast.Term;
import edu.harvard.seas.pl.formulog.ast.UserPredicate;
import edu.harvard.seas.pl.formulog.ast.Var;
import edu.harvard.seas.pl.formulog.db.IndexedFactDb;
import edu.harvard.seas.pl.formulog.db.SortedIndexedFactDb.SortedIndexedFactDbBuilder;
import edu.harvard.seas.pl.formulog.magic.MagicSetTransformer;
import edu.harvard.seas.pl.formulog.symbols.RelationSymbol;
import edu.harvard.seas.pl.formulog.types.WellTypedProgram;
import edu.harvard.seas.pl.formulog.unification.OverwriteSubstitution;
import edu.harvard.seas.pl.formulog.validating.InvalidProgramException;
import edu.harvard.seas.pl.formulog.validating.ValidRule;
import edu.harvard.seas.pl.formulog.validating.ast.Assignment;
import edu.harvard.seas.pl.formulog.validating.ast.Check;
import edu.harvard.seas.pl.formulog.validating.ast.Destructor;
import edu.harvard.seas.pl.formulog.validating.ast.SimpleConjunctExnVisitor;
import edu.harvard.seas.pl.formulog.validating.ast.SimplePredicate;
import edu.harvard.seas.pl.formulog.validating.ast.SimpleRule;

public class NaiveEvaluation implements Evaluation {

	private final IndexedFactDb db;
	private final Map<RelationSymbol, Iterable<IndexedRule>> rules;
	private final UserPredicate query;

	public static NaiveEvaluation setup(WellTypedProgram prog) throws InvalidProgramException {
		MagicSetTransformer mst = new MagicSetTransformer(prog);
		BasicProgram magicProg;
		if (prog.hasQuery()) {
			magicProg = mst.transformForQuery(prog.getQuery(), true, true);
		} else {
			magicProg = mst.transform(true, true);
		}
		Set<RelationSymbol> allRelations = new HashSet<>(magicProg.getFactSymbols());
		allRelations.addAll(magicProg.getRuleSymbols());
		SortedIndexedFactDbBuilder dbb = new SortedIndexedFactDbBuilder(allRelations);
		Map<RelationSymbol, Iterable<IndexedRule>> rules = new HashMap<>();
		for (RelationSymbol sym : magicProg.getRuleSymbols()) {
			List<IndexedRule> rs = new ArrayList<>();
			for (BasicRule br : magicProg.getRules(sym)) {
				ValidRule vr = ValidRule.make(br, (p, vs) -> 0);
				SimpleRule sr = SimpleRule.make(vr);
				IndexedRule ir = IndexedRule.make(sr, dbb::makeIndex);
				System.out.println(ir);
				rs.add(ir);
			}
			rules.put(sym, rs);
		}
		IndexedFactDb db = dbb.build();
		for (RelationSymbol sym : magicProg.getFactSymbols()) {
			for (Term[] args : magicProg.getFacts(sym)) {
				db.add(sym, args);
			}
		}
		return new NaiveEvaluation(db, rules, magicProg.getQuery());
	}

	private NaiveEvaluation(IndexedFactDb db, Map<RelationSymbol, Iterable<IndexedRule>> rules, UserPredicate query) {
		this.db = db;
		this.rules = rules;
		this.query = query;
	}

	@Override
	public synchronized void run(int parallelism) throws EvaluationException {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (Iterable<IndexedRule> rs : rules.values()) {
				for (IndexedRule r : rs) {
					changed |= evaluateRule(r, 0, new OverwriteSubstitution());
				}
			}
		}
	}

	private boolean evaluateRule(IndexedRule r, int pos, OverwriteSubstitution s) throws EvaluationException {
		if (pos >= r.getBodySize()) {
			SimplePredicate hd = r.getHead().normalize(s);
			return db.add(hd.getSymbol(), hd.getArgs());
		}
		return r.getBody(pos).accept(new SimpleConjunctExnVisitor<Void, Boolean, EvaluationException>() {

			@Override
			public Boolean visit(Assignment assignment, Void input) throws EvaluationException {
				assignment.assign(s);
				return evaluateRule(r, pos + 1, s);
			}

			@Override
			public Boolean visit(Check check, Void input) throws EvaluationException {
				return check.check(s) && evaluateRule(r, pos + 1, s);
			}

			@Override
			public Boolean visit(Destructor destructor, Void input) throws EvaluationException {
				return destructor.destruct(s) && evaluateRule(r, pos + 1, s);
			}

			@Override
			public Boolean visit(SimplePredicate predicate, Void input) throws EvaluationException {
				int idx = r.getDBIndex(pos);
				Term[] args = predicate.getArgs();
				Term[] key = new Term[args.length];
				boolean[] pat = predicate.getBindingPattern();
				for (int i = 0; i < args.length; ++i) {
					if (pat[i]) {
						key[i] = args[i].normalize(s);
					} else {
						key[i] = args[i];
					}
				}
				boolean changed = false;
				Iterable<Term[]> answers = db.get(key, idx);
				if (predicate.isNegated()) {
					return !answers.iterator().hasNext() && evaluateRule(r, pos + 1, s);
				} else {
					for (Term[] ans : answers) {
						for (int i = 0; i < pat.length; ++i) {
							if (!pat[i]) {
								s.put((Var) key[i], ans[i]);
							}
						}
						changed |= evaluateRule(r, pos + 1, s);
					}
					return changed;
				}
			}

		}, null);
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
				if (query == null) {
					return null;
				}
				RelationSymbol querySym = query.getSymbol();
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

	private static class FactIterator implements Iterator<UserPredicate> {

		private final RelationSymbol sym;
		private final Iterator<Term[]> bindings;

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

}