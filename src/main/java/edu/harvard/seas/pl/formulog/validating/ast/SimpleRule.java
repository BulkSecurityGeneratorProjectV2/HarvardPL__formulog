package edu.harvard.seas.pl.formulog.validating.ast;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.harvard.seas.pl.formulog.ast.AbstractRule;
import edu.harvard.seas.pl.formulog.ast.ComplexLiteral;
import edu.harvard.seas.pl.formulog.ast.ComplexLiterals.ComplexLiteralExnVisitor;
import edu.harvard.seas.pl.formulog.ast.Constructor;
import edu.harvard.seas.pl.formulog.ast.Term;
import edu.harvard.seas.pl.formulog.ast.UnificationPredicate;
import edu.harvard.seas.pl.formulog.ast.UserPredicate;
import edu.harvard.seas.pl.formulog.ast.Var;
import edu.harvard.seas.pl.formulog.validating.InvalidProgramException;
import edu.harvard.seas.pl.formulog.validating.ValidRule;

public class SimpleRule extends AbstractRule<SimplePredicate, SimpleLiteral> {

	public static SimpleRule make(ValidRule rule) throws InvalidProgramException {
		Simplifier simplifier = new Simplifier();
		for (ComplexLiteral atom : rule) {
			try {
				simplifier.add(atom);
			} catch (InvalidProgramException e) {
				throw new InvalidProgramException("Problem simplifying this rule:\n" + rule
						+ "\nCould not simplify this atom: " + atom + "\nReason:\n" + e.getMessage());
			}
		}
		UserPredicate head = rule.getHead();
		Set<Var> boundVars = simplifier.getBoundVars();
		if (!boundVars.containsAll(head.varSet())) {
			throw new InvalidProgramException("Unbound variables in head of rule:\n" + rule);
		}
		SimplePredicate newHead = SimplePredicate.make(head.getSymbol(), head.getArgs(), boundVars, head.isNegated());
		return new SimpleRule(newHead, simplifier.getConjuncts());
	}

	// XXX This isn't great because it doesn't check to make sure the invariants of
	// a SimpleRule are actually maintained.
	public static SimpleRule make(SimplePredicate head, List<SimpleLiteral> body) {
		return new SimpleRule(head, body);
	}

	private SimpleRule(SimplePredicate head, List<SimpleLiteral> body) {
		super(head, body);
	}

	private static class Simplifier {

		private final List<SimpleLiteral> acc = new ArrayList<>();
		private final Set<Var> boundVars = new HashSet<>();

		public void add(ComplexLiteral atom) throws InvalidProgramException {
			List<ComplexLiteral> todo = new ArrayList<>();
			SimpleLiteral c = atom.accept(new ComplexLiteralExnVisitor<Void, SimpleLiteral, InvalidProgramException>() {

				@Override
				public SimpleLiteral visit(UnificationPredicate unificationPredicate, Void input)
						throws InvalidProgramException {
					Term lhs = unificationPredicate.getLhs();
					Term rhs = unificationPredicate.getRhs();
					boolean leftBound = boundVars.containsAll(lhs.varSet());
					boolean rightBound = boundVars.containsAll(rhs.varSet());
					if (unificationPredicate.isNegated() && !(leftBound && rightBound)) {
						throw new InvalidProgramException();
					}
					if (leftBound && rightBound) {
						return Check.make(lhs, rhs, unificationPredicate.isNegated());
					} else if (rightBound) {
						if (lhs instanceof Var) {
							return Assignment.make((Var) lhs, rhs);
						}
						if (!(lhs instanceof Constructor)) {
							throw new InvalidProgramException();
						}
						return makeDestructor(rhs, (Constructor) lhs, todo);
					} else if (leftBound) {
						if (rhs instanceof Var) {
							return Assignment.make((Var) rhs, lhs);
						}
						if (!(rhs instanceof Constructor)) {
							throw new InvalidProgramException();
						}
						return makeDestructor(lhs, (Constructor) rhs, todo);
					} else {
						if (!(lhs instanceof Constructor) || !(rhs instanceof Constructor)) {
							throw new InvalidProgramException();
						}
						Constructor c1 = (Constructor) lhs;
						Constructor c2 = (Constructor) rhs;
						if (!c1.getSymbol().equals(c2.getSymbol())) {
							throw new InvalidProgramException("Unsatisfiable unification conjunct");
						}
						List<ComplexLiteral> cs = new ArrayList<>();
						Term[] args1 = c1.getArgs();
						Term[] args2 = c2.getArgs();
						for (int i = 0; i < args1.length; ++i) {
							cs.add(UnificationPredicate.make(args1[i], args2[i], false));
						}
						ValidRule.order(cs, (p, xs) -> 1, new HashSet<>(boundVars));
						for (ComplexLiteral c : cs) {
							todo.add(c);
						}
						return null;
					}
				}

				private Destructor makeDestructor(Term boundTerm, Constructor unboundCtor, List<ComplexLiteral> todo) {
					Term[] args = unboundCtor.getArgs();
					Var[] vars = new Var[args.length];
					for (int i = 0; i < args.length; ++i) {
						Var y = Var.getFresh(false);
						vars[i] = y;
						todo.add(UnificationPredicate.make(y, args[i], false));
					}
					return Destructor.make(boundTerm, unboundCtor.getSymbol(), vars);
				}

				@Override
				public SimpleLiteral visit(UserPredicate userPredicate, Void input) {
					Term[] args = userPredicate.getArgs();
					Term[] newArgs = new Term[args.length];
					Set<Var> seen = new HashSet<>();
					for (int i = 0; i < args.length; ++i) {
						Term arg = args[i];
						if (boundVars.containsAll(arg.varSet())) {
							newArgs[i] = arg;
						} else if (arg instanceof Var && seen.add((Var) arg)) {
							newArgs[i] = arg;
						} else {
							Var y = Var.getFresh(false);
							newArgs[i] = y;
							todo.add(UnificationPredicate.make(y, arg, false));
						}
					}
					SimpleLiteral c = SimplePredicate.make(userPredicate.getSymbol(), newArgs, boundVars,
							userPredicate.isNegated());
					return c;
				}

			}, null);
			if (c != null) {
				acc.add(c);
				boundVars.addAll(c.varSet());
			}
			for (ComplexLiteral x : todo) {
				add(x);
			}
		}

		public List<SimpleLiteral> getConjuncts() {
			return acc;
		}

		public Set<Var> getBoundVars() {
			return boundVars;
		}

	}

}
