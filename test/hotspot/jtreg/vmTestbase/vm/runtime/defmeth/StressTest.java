/*
 * Copyright (c) 2013, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package vm.runtime.defmeth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import nsk.share.TestFailure;
import nsk.share.test.StressOptions;
import nsk.share.test.Stresser;
import vm.runtime.defmeth.shared.Constants;
import vm.runtime.defmeth.shared.DefMethTest;
import vm.runtime.defmeth.shared.ExecutionMode;
import vm.runtime.defmeth.shared.builder.TestBuilder;
import vm.share.options.Option;
import vm.share.options.OptionSupport;
import vm.share.options.Options;
import jdk.test.lib.Utils;
import static jdk.internal.org.objectweb.asm.Opcodes.*;

/*
 * Stress test for default methods implementation.
 *
 * Stress scenario is the following:
 *   - in multiple threads ...
 *   - ... continuously run random tests ...
 *   - ... in random configuration ...
 *   - ... until predefined period of time is over...
 *   - ... or any failures occured.
 */
public class StressTest implements Runnable {
    @Options
    private StressOptions opts = new StressOptions();

    @Option(name="seed", default_value="0", description="force deterministic behavior")
    private long seed;

    @Option(name="redefine", default_value="false", description="use scenarios w/ class redefinition")
    private boolean doRedefine;

    @Option(name="ver", default_value="49", description="minimum class file version to be used in the tests")
    private int minMajorVer;

    @Option(name="ignoreTestFailures", default_value="false", description="ignore failures of the executed tests")
    private boolean ignoreTestFailures;

    class Worker extends Thread {
        private final Random rand;

        private volatile DefMethTest failedTest;
        private Throwable reason;
        private volatile long executedTests = 0;

        public Worker(String id, long seed) {
            setName(id);
            this.rand = new Random(seed);
        }

        @Override
        public void run() {
            while (!Thread.interrupted()) {
                int idx = rand.nextInt(testlist.size());
                DefMethTest test = testlist.get(idx);
                try {
                    test.run();
                    executedTests++;
                    if (test.isFailed()) {
                        throw new TestFailure(test.toString());
                    }
                } catch (Throwable e) {
                    if (!ignoreTestFailures) {
                        failedTest = test;
                        reason = e;
                        break;
                    }
                }
            }
        }

        public boolean isFailed() { return failedTest != null; }
        public Throwable getReason() { return reason; }
        public DefMethTest getFailedTest() { return failedTest; }
        public long getExecutedTests() { return executedTests; }
    }

    private List<DefMethTest> testlist;

    private Worker[] workers;

    Stresser stresser;

    public static void main(String[] args) {
        StressTest test = new StressTest();
        OptionSupport.setupAndRun(test, args);
    }

    @Override
    public void run() {
        configureTests();
        startWorkers();

        stresser = new Stresser(opts);
        try {
            stresser.start(0);
            while (workersAlive() && stresser.continueExecution()) {
                printStats();

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {}
            }
        } finally {
            interruptWorkers();
            joinWorkers();

            stresser.finish();
        }
    }

    private void configureTests() {
        int[] majorVerValues = new int[52 - minMajorVer + 1];
        for (int i = 0; i< majorVerValues.length; i++) {
            majorVerValues[i] = minMajorVer + i;
        }

        int[] flagsValues = new int[] {
            0,
            ACC_STRICT,
            ACC_SYNCHRONIZED,
            ACC_STRICT | ACC_SYNCHRONIZED
        };

        boolean[] doRedefineValues;
        if (doRedefine) {
            doRedefineValues = new boolean[] { true, false};
        } else {
            doRedefineValues = new boolean[] { false };
        }

        // Upper limit for test count
        int testCount = DefMethTest.getTests().size() * majorVerValues.length
                        * flagsValues.length * doRedefineValues.length;

        testlist = new ArrayList<>(testCount);

        // Enumerate all tests in all possible modes
        for (Class<? extends DefMethTest> testClass : DefMethTest.getTests()) {
            for (ExecutionMode mode : ExecutionMode.values()) {
                // Skip REDEFINITION execmode, the top README file indicates that it isn't a
                // valid execution mode and there's also no code supporting this in the test generator.
                if ("REDEFINITION".equals(mode.toString())) {
                    continue;
                }

                for (int majorVer : majorVerValues) {
                    for (int flags : flagsValues ) {
                        for (boolean redefine : doRedefineValues) {
                            // RedefineTest isn't applicable to reflection-based execution scenario (REDEFINE & INVOKE_WITH_ARGS)
                            if (testClass == RedefineTest.class && mode.isReflectionBased()) {
                                continue;
                            }

                            // Only run the RedefineTest tests when redefining
                            if (!redefine && testClass == RedefineTest.class) {
                                continue;
                            }

                            try {
                                DefMethTest test = testClass.newInstance();

                                OptionSupport.setup(test, new String[] {
                                        "-execMode", mode.toString(),
                                        "-ver", Integer.toString(majorVer),
                                        "-flags", Integer.toString(flags),
                                        "-redefine", Boolean.toString(redefine),
                                        "-ignoreCrashes",
                                        "-ignoreKnownFailures",
                                        "-silent",
                                        "-failfast"});

                                testlist.add(test);
                            } catch (InstantiationException | IllegalAccessException ex) {
                                throw new TestFailure(ex);
                            }
                        }
                    }
                }
            }
        }

        System.out.printf("Testlist size: %d\n", testlist.size());
    }

    private void startWorkers() {
        Random rand;
        if (seed == 0) {
            seed = Utils.SEED;
        }

        System.out.printf("Seed: %d\n", seed);
        rand = new Random(seed);

        //Workaround for the deadlock caused by
        // JDK-7122142: "(ann) Race condition between isAnnotationPresent and getAnnotations"
        try {
            // Do a warm-up cycle
            for (Class<? extends DefMethTest> testClass : DefMethTest.getTests()) {
                DefMethTest test = testClass.newInstance();

                OptionSupport.setupAndRun(test,
                        new String[] { "-silent", "-ignoreKnownFailures"});
            }
        } catch(InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        int threadsCount = opts.getThreadsFactor();
        if (threadsCount == 1) {
            threadsCount = 5;
        }

        workers = new Worker[threadsCount];

        System.out.printf("Spawning %d workers...\n", workers.length);

        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Worker(
                    String.format("Worker #%d/%d", i+1, workers.length),
                    rand.nextLong());
        }

        for (Worker worker : workers) {
            worker.start();
        }
    }

    private void interruptWorkers() {
        for (Worker worker : workers) {
            worker.interrupt();
        }
    }

    private void joinWorkers() {
        boolean isFailed = false;

        for (Worker worker : workers) {
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException e) {}
            }

            System.out.printf("%s: %s (executed: %d)\n",
                    worker.getName(),
                    worker.isFailed() ? "FAILED: " + worker.getFailedTest() : "PASSED",
                    worker.getExecutedTests());

            if (worker.isFailed()) {
                if (Constants.PRINT_STACK_TRACE) {
                    worker.getReason().printStackTrace();
                }

                isFailed = true;
            }
        }

        if (isFailed) {
            throw new TestFailure("Some of the worker threads failed.");
        }
    }

    private boolean workersAlive() {
        for (Worker worker : workers) {
            if (!worker.isAlive()) {
                return false;
            }
        }

        return true;
    }

    private void printStats() {
        long[] counts = new long[workers.length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = workers[i].executedTests;
        }

        StringBuilder msg = new StringBuilder();
        msg.append(stresser.getTimeLeft() / 1000).append("s left: ");
        msg.append(Arrays.toString(counts));

        System.out.println(msg);
    }
}
