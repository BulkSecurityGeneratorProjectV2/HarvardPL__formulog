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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.harvard.seas.pl.formulog.ast.BasicRule;
import edu.harvard.seas.pl.formulog.ast.ComplexLiteral;
import edu.harvard.seas.pl.formulog.ast.ComplexLiterals.ComplexLiteralVisitor;
import edu.harvard.seas.pl.formulog.ast.Term;
import edu.harvard.seas.pl.formulog.ast.UnificationPredicate;
import edu.harvard.seas.pl.formulog.ast.UserPredicate;
import edu.harvard.seas.pl.formulog.ast.Var;
import edu.harvard.seas.pl.formulog.symbols.RelationSymbol;
import edu.harvard.seas.pl.formulog.unification.Unification;
import edu.harvard.seas.pl.formulog.validating.InvalidProgramException;

public final class Adornments {

	private Adornments() {
		throw new AssertionError();
	}

	public static UserPredicate adorn(UserPredicate a, Set<Var> boundVars, boolean topDownIsDefault) {
		RelationSymbol origSym = a.getSymbol();
		if (!topDownIsDefault && !origSym.isTopDown()) {
			return a;
		}
		boolean defaultAdornment = !origSym.isBottomUp();
		Term[] args = a.getArgs();
		boolean[] adornment = new boolean[args.length];
		for (int k = 0; k < args.length; k++) {
			adornment[k] = defaultAdornment && boundVars.containsAll(args[k].varSet());
		}
		AdornedSymbol sym = new AdornedSymbol(origSym, adornment);
		return UserPredicate.make(sym, args, a.isNegated());
	}

	public static BasicRule adornRule(UserPredicate head, List<ComplexLiteral> body, boolean topDownIsDefault)
			throws InvalidProgramException {
		RelationSymbol sym = head.getSymbol();
		boolean[] headAdornment;
		if (sym instanceof AdornedSymbol) {
			headAdornment = ((AdornedSymbol) head.getSymbol()).getAdornment();
		} else {
			headAdornment = new boolean[sym.getArity()];
			for (int i = 0; i < headAdornment.length; ++i) {
				headAdornment[i] = false;
			}
		}
		Set<Var> boundVars = new HashSet<>();
		Term[] headArgs = head.getArgs();
		for (int i = 0; i < headArgs.length; i++) {
			if (headAdornment[i]) {
				boundVars.addAll(headArgs[i].varSet());
			}
		}
		List<ComplexLiteral> newBody = new ArrayList<>(body);
		for (int i = 0; i < newBody.size(); i++) {
			boolean ok = false;
			for (int j = i; j < newBody.size(); j++) {
				ComplexLiteral a = body.get(j);
				if (Unification.canBindVars(a, boundVars)) {
					Collections.swap(body, i, j);
					int pos = i;
					a.accept(new ComplexLiteralVisitor<Void, Void>() {

						@Override
						public Void visit(UnificationPredicate unificationPredicate, Void input) {
							return null;
						}

						@Override
						public Void visit(UserPredicate userPredicate, Void input) {
							if (userPredicate.getSymbol().isIdbSymbol()) {
								newBody.set(pos, adorn(userPredicate, boundVars, topDownIsDefault));
							}
							return null;
						}

					}, null);
					boundVars.addAll(a.varSet());
					ok = true;
					break;
				}
			}
			if (!ok) {
				throw new InvalidProgramException(
						"Cannot reorder rule to meet well-modeness restrictions: " + BasicRule.make(head, body));
			}
		}
		return BasicRule.make(head, body);
	}

}
