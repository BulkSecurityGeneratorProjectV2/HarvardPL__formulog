package edu.harvard.seas.pl.formulog;

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

import java.io.PrintStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import edu.harvard.seas.pl.formulog.ast.Rule;
import edu.harvard.seas.pl.formulog.db.IndexedFactDb;
import edu.harvard.seas.pl.formulog.symbols.FunctionSymbol;
import edu.harvard.seas.pl.formulog.symbols.RelationSymbol;
import edu.harvard.seas.pl.formulog.util.Util;

public final class Configuration {

	private Configuration() {
		throw new AssertionError("impossible");
	}

	public static final boolean recordFuncDiagnostics = propIsSet("timeFuncs");
	private static final Map<FunctionSymbol, AtomicLong> funcTimes = new ConcurrentHashMap<>();

	public static final boolean recordRuleDiagnostics = propIsSet("timeRules");
	private static final Map<Rule<?, ?>, AtomicLong> ruleTimes = new ConcurrentHashMap<>();

	public static final boolean timeSmt = propIsSet("timeSmt");
	private static final AtomicLong smtEvalTime = new AtomicLong();
	private static final AtomicLong smtDeclTime = new AtomicLong();
	private static final AtomicLong smtInferTime = new AtomicLong();
	private static final AtomicLong smtSerialTime = new AtomicLong();
	private static final AtomicLong smtWaitTime = new AtomicLong();

	public static final boolean printRelSizes = propIsSet("printRelSizes");
	public static final boolean noResults = propIsSet("noResults");

	public static final int optimizationSetting = getIntProp("optimize", 0);

	public static final int taskSize = getIntProp("taskSize", 128);
	public static final int smtTaskSize = getIntProp("smtTaskSize", 8);

	public static final int memoizeThreshold() {
		return getIntProp("memoizeThreshold", 500);
	}

	public static boolean noModel() {
		return propIsSet("noModel");
	}

	static {
		if (recordFuncDiagnostics) {
			Runtime.getRuntime().addShutdownHook(new Thread() {

				@Override
				public void run() {
					printFuncDiagnostics(System.err);
				}

			});
		}
		if (recordRuleDiagnostics) {
			Runtime.getRuntime().addShutdownHook(new Thread() {

				@Override
				public void run() {
					printRuleDiagnostics(System.err);
				}

			});
		}
		if (timeSmt) {
			Runtime.getRuntime().addShutdownHook(new Thread() {

				@Override
				public void run() {
					printSmtDiagnostics(System.err);
				}

			});
		}
		System.err.println("[CONFIG] noResults=" + noResults);
		System.err.println("[CONFIG] timeRules=" + recordRuleDiagnostics);
		System.err.println("[CONFIG] timeFuncs=" + recordFuncDiagnostics);
		System.err.println("[CONFIG] timeSmt=" + timeSmt);
		System.err.println("[CONFIG] optimize=" + optimizationSetting);
		System.err.println("[CONFIG] taskSize=" + taskSize);
		System.err.println("[CONFIG] smtTaskSize=" + smtTaskSize);
		System.err.println("[CONFIG] memoizeThreshold=" + memoizeThreshold());
		System.err.println("[CONFIG] noModel=" + noModel());
	}

	public static void recordSmtDeclTime(long time) {
		smtDeclTime.addAndGet(time);
	}
	
	public static void recordSmtInferTime(long time) {
		smtInferTime.addAndGet(time);
	}
	
	public static void recordSmtSerialTime(long time) {
		smtSerialTime.addAndGet(time);
	}
	
	public static void recordSmtEvalTime(long time) {
		smtEvalTime.addAndGet(time);
	}
	
	public static void recordSmtWaitTime(long time) {
		smtWaitTime.addAndGet(time);
	}
	
	public static synchronized void printSmtDiagnostics(PrintStream out) {
		System.err.println("[SMT DECL TIME] " + smtDeclTime.get() + "ms");
		System.err.println("[SMT INFER TIME] " + smtInferTime.get() + "ms");
		System.err.println("[SMT SERIAL TIME] " + smtSerialTime.get() + "ms");
		System.err.println("[SMT EVAL TIME] " + smtEvalTime.get() + "ms");
		System.err.println("[SMT WAIT TIME] " + smtWaitTime.get() + "ms");
	}

	public static void recordFuncTime(FunctionSymbol func, long time) {
		AtomicLong l = Util.lookupOrCreate(funcTimes, func, () -> new AtomicLong());
		l.addAndGet(time);
	}

	public static Map<FunctionSymbol, AtomicLong> getFuncDiagnostics() {
		return Collections.unmodifiableMap(funcTimes);
	}

	public static synchronized void printFuncDiagnostics(PrintStream out) {
		Map<FunctionSymbol, AtomicLong> times = Configuration.getFuncDiagnostics();
		List<Map.Entry<FunctionSymbol, AtomicLong>> sorted = times.entrySet().stream().sorted(sortTimes)
				.collect(Collectors.toList());
		Iterator<Map.Entry<FunctionSymbol, AtomicLong>> it = sorted.iterator();
		int end = Math.min(times.size(), 10);
		for (int i = 0; i < end; ++i) {
			Map.Entry<FunctionSymbol, AtomicLong> e = it.next();
			out.println("[FUNC DIAGNOSTICS] " + e.getValue().get() + "ms: " + e.getKey());
		}
	}

	private static final Comparator<Map.Entry<?, AtomicLong>> sortTimes = new Comparator<Map.Entry<?, AtomicLong>>() {

		@Override
		public int compare(Entry<?, AtomicLong> e1, Entry<?, AtomicLong> e2) {
			return -Long.compare(e1.getValue().get(), e2.getValue().get());
		}

	};

	public static void recordRuleTime(Rule<?, ?> rule, long time) {
		AtomicLong l = Util.lookupOrCreate(ruleTimes, rule, () -> new AtomicLong());
		l.addAndGet(time);
	}

	public static Map<Rule<?, ?>, AtomicLong> getRuleDiagnostics() {
		return Collections.unmodifiableMap(ruleTimes);
	}

	public static synchronized void printRuleDiagnostics(PrintStream out) {
		Map<Rule<?, ?>, AtomicLong> times = Configuration.getRuleDiagnostics();
		List<Map.Entry<Rule<?, ?>, AtomicLong>> sorted = times.entrySet().stream().sorted(sortTimes)
				.collect(Collectors.toList());
		Iterator<Map.Entry<Rule<?, ?>, AtomicLong>> it = sorted.iterator();
		int end = Math.min(times.size(), 10);
		for (int i = 0; i < end; ++i) {
			Map.Entry<Rule<?, ?>, AtomicLong> e = it.next();
			out.println("[RULE DIAGNOSTICS] " + e.getValue().get() + "ms:\n" + e.getKey());
		}
	}
	
	public static synchronized void printRelSizes(PrintStream out, IndexedFactDb db) {
		for (RelationSymbol sym : db.getSymbols()) {
			out.println("[REL SIZE] " + sym + ": " + db.getAll(sym).size());
		}
	}

	private static boolean propIsSet(String prop) {
		String val = System.getProperty(prop);
		if (val == null) {
			return false;
		}
		if (!(val.equals("true") || val.equals(""))) {
			throw new IllegalArgumentException("Property " + prop + " does not take an argument: " + val);
		}
		return val != null;
	}

	private static int getIntProp(String prop, int def) {
		String val = System.getProperty(prop);
		if (val == null) {
			return def;
		}
		try {
			return Integer.parseInt(val);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Property " + prop + " expects an integer argument");
		}
	}

}
