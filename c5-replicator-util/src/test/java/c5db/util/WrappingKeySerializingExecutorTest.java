/*
 * Copyright 2014 WANdisco
 *
 *  WANdisco licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package c5db.util;

import c5db.CollectionMatchers;
import c5db.ConcurrencyTestUtil;
import c5db.FutureMatchers;
import org.hamcrest.Matcher;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.States;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static c5db.ConcurrencyTestUtil.runAConcurrencyTestSeveralTimes;
import static c5db.ConcurrencyTestUtil.runNTimesAndWaitForAllToComplete;
import static c5db.FutureMatchers.resultsIn;
import static com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.IsEqual.equalTo;

public class WrappingKeySerializingExecutorTest {
  @Rule
  public JUnitRuleMockery context = new JUnitRuleMockery();
  private final ExecutorService fixedThreadExecutor = Executors.newFixedThreadPool(3);
  private final ExecutorService executorService = context.mock(ExecutorService.class);

  private static int numTasks = 20;

  @SuppressWarnings("unchecked")
  private final CheckedSupplier<Integer, Exception> task = context.mock(CheckedSupplier.class);

  @After
  public void shutdownExecutorService() {
    fixedThreadExecutor.shutdownNow();
  }

  @Test
  public void runsTasksSubmittedToItAndReturnsTheirResult() throws Exception {
    KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(sameThreadExecutor());

    context.checking(new Expectations() {{
      oneOf(task).get();
      will(returnValue(3));
    }});

    assertThat(keySerializingExecutor.submit("key", task), resultsIn(equalTo(3)));
  }

  @Test
  public void returnsFuturesSetWithTheExceptionsThrownBySubmittedTasks() throws Exception {
    KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(sameThreadExecutor());

    context.checking(new Expectations() {{
      oneOf(task).get();
      will(throwException(new ArithmeticException("Expected as part of test")));
    }});

    assertThat(keySerializingExecutor.submit("key", task), FutureMatchers.<Integer>resultsInException(ArithmeticException.class));
  }

  @Test
  public void submitsTasksOnceEachToTheSuppliedExecutorService() throws Exception {
    KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(executorService);

    context.checking(new Expectations() {{
      allowSubmitOrExecuteOnce(context, executorService);
    }});

    keySerializingExecutor.submit("key", task);
  }

  @Test(timeout = 1000)
  public void executesTasksAllHavingTheSameKeyInSeries() throws Exception {
    KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(fixedThreadExecutor);

    List<Integer> log =
        submitSeveralTasksAndBeginLoggingTheirInvocations(keySerializingExecutor, "key");

    waitForTasksToFinish(keySerializingExecutor, "key");

    assertThat(log, containsRecordOfEveryTask());
    assertThat(log, (Matcher<? super List<Integer>>) isInTheOrderTheTasksWereSubmitted());
  }

  @Test(timeout = 1000)
  public void executesTasksForDifferentKeysEachSeparatelyInSeries() throws Exception {
    KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(fixedThreadExecutor);

    List<Integer> log1 =
        submitSeveralTasksAndBeginLoggingTheirInvocations(keySerializingExecutor, "key1");
    List<Integer> log2 =
        submitSeveralTasksAndBeginLoggingTheirInvocations(keySerializingExecutor, "key2");

    waitForTasksToFinish(keySerializingExecutor, "key1");
    waitForTasksToFinish(keySerializingExecutor, "key2");

    assertThat(log1, containsRecordOfEveryTask());
    assertThat(log1, (Matcher<? super List<Integer>>) isInTheOrderTheTasksWereSubmitted());
    assertThat(log2, containsRecordOfEveryTask());
    assertThat(log2, (Matcher<? super List<Integer>>) isInTheOrderTheTasksWereSubmitted());
  }

  @Test(expected = RejectedExecutionException.class)
  public void throwsAnExceptionIfATaskIsSubmittedAfterShutdownIsCalled() throws Exception {
    KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(fixedThreadExecutor);
    keySerializingExecutor.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
    keySerializingExecutor.submit("key", new CheckedSupplier<Object, Exception>() {
      @Override
      public Object get() throws Exception {
        return null;
      }
    });
  }

  @Test
  public void onShutdownCompletesAllTasksThatHadBeenSubmittedPriorToShutdown() throws Exception {
    KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(fixedThreadExecutor);

    List<Integer> log =
        submitSeveralTasksAndBeginLoggingTheirInvocations(keySerializingExecutor, "key");

    keySerializingExecutor.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

    assertThat(log, containsRecordOfEveryTask());
  }

  @Test(timeout = 5000)
  public void acceptsSubmissionsFromMultipleThreadsConcurrentlyWithEachThreadADifferentKey() throws Exception {
    final int numThreads = 20;
    final int numAttempts = 150;

    runAConcurrencyTestSeveralTimes(numThreads, numAttempts, new ConcurrencyTestUtil.ConcurrencyTest() {
      @Override
      public void run(int numberOfSubmissions, ExecutorService taskSubmitter) throws Exception {
        WrappingKeySerializingExecutorTest.this.executeAMultikeySubmissionConcurrencyStressTest(numberOfSubmissions, taskSubmitter);
      }
    });
  }

  @Test(timeout = 5000)
  public void acceptsSubmissionsFromMultipleThreadsConcurrentlyWithinOneKeyWithExecutionOrderUndetermined()
      throws Exception {
    final int numThreads = 50;
    final int numAttempts = 300;

    runAConcurrencyTestSeveralTimes(numThreads, numAttempts, new ConcurrencyTestUtil.ConcurrencyTest() {
      @Override
      public void run(int numCalls, ExecutorService executor) throws Exception {
        WrappingKeySerializingExecutorTest.this.executeASingleKeyConcurrencyStressTest(numCalls, executor);
      }
    });
  }

  @Test(timeout = 5000)
  public void shutsDownIdempotently() throws Exception {
    final int numThreads = 10;
    final int numAttempts = 300;

    runAConcurrencyTestSeveralTimes(numThreads, numAttempts, new ConcurrencyTestUtil.ConcurrencyTest() {
      @Override
      public void run(int numShutdownCalls, ExecutorService shutdownCallingService) throws Exception {
        WrappingKeySerializingExecutorTest.this.executeAShutdownIdempotencyStressTest(numShutdownCalls, shutdownCallingService);
      }
    });
  }

  @Test(timeout = 5000)
  public void shutsDownAtomicallyWithRespectToSubmitAttempts() throws Exception {
    final int numThreads = 5;
    final int numAttempts = 100;

    runAConcurrencyTestSeveralTimes(numThreads, numAttempts, new ConcurrencyTestUtil.ConcurrencyTest() {
      @Override
      public void run(int numberOfSubmissions, ExecutorService executor) throws Exception {
        WrappingKeySerializingExecutorTest.this.executeAShutdownAtomicityStressTest(numberOfSubmissions, executor);
      }
    });
  }


  private static List<Integer> submitSeveralTasksAndBeginLoggingTheirInvocations(
      KeySerializingExecutor keySerializingExecutor, String key) {

    List<Integer> log = new ArrayList<>(numTasks * 2);
    for (int i = 0; i < numTasks; i++) {
      keySerializingExecutor.submit(key, getSupplierWhichLogsItsNumberTwice(i, log));
    }
    return log;
  }

  private static CheckedSupplier<Integer, Exception> getSupplierWhichLogsItsNumberTwice(
      final int instanceNumber, final List<Integer> log) {

    return new CheckedSupplier<Integer, Exception>() {
      @Override
      public Integer get() throws Exception {
        log.add(instanceNumber);
        Thread.yield();
        log.add(instanceNumber);
        return 0;
      }
    };
  }

  private static void waitForTasksToFinish(KeySerializingExecutor keySerializingExecutor, String key)
      throws Exception {
    keySerializingExecutor.submit(key, new CheckedSupplier<Object, Exception>() {
      @Override
      public Object get() throws Exception {
        return 0;
      }
    }).get();
  }

  public static void allowSubmitOrExecuteOnce(final Mockery context, final ExecutorService executorService) {
    final States submitted = context.states("submitted").startsAs("no");

    context.checking(new Expectations() {{
      allowSubmitAndThen(context, executorService, submitted.is("yes"));
      doNowAllowSubmitOnce(context, executorService, submitted.is("yes"));
    }});
  }

  @SuppressWarnings("unchecked")
  private static void allowSubmitAndThen(Mockery context,
                                         final ExecutorService executorService,
                                         final org.jmock.internal.State state) {
    context.checking(new Expectations() {{
      allowing(executorService).submit(with.<Callable>is(any(Callable.class)));
      then(state);
      allowing(executorService).submit((Runnable) with.is(any(Runnable.class)), with.is(any(Object.class)));
      then(state);
      allowing(executorService).submit(with.<Runnable>is(any(Runnable.class)));
      then(state);
      allowing(executorService).execute((Runnable) with.is(any(Runnable.class)));
      then(state);
    }});
  }

  @SuppressWarnings("unchecked")
  private static void doNowAllowSubmitOnce(Mockery context,
                                           final ExecutorService executorService,
                                           final org.jmock.internal.State state) {
    context.checking(new Expectations() {{
      never(executorService).submit(with.<Callable>is(any(Callable.class)));
      when(state);
      never(executorService).submit((Runnable) with.is(any(Runnable.class)), with.is(any(Object.class)));
      when(state);
      never(executorService).submit(with.<Runnable>is(any(Runnable.class)));
      when(state);
      never(executorService).execute((Runnable) with.is(any(Runnable.class)));
      when(state);
    }});
  }

  private void executeAMultikeySubmissionConcurrencyStressTest(int numberOfSubmissions, ExecutorService taskSubmitter)
      throws Exception {
    final KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(
        Executors.newSingleThreadExecutor());

    runSeveralSimultaneousSeriesOfTasksAndWaitForAllToComplete(
        numberOfSubmissions, taskSubmitter, keySerializingExecutor);

    keySerializingExecutor.shutdownAndAwaitTermination(2, TimeUnit.SECONDS);
  }

  private void runSeveralSimultaneousSeriesOfTasksAndWaitForAllToComplete(
      int numSimultaneous,
      ExecutorService executorThatSubmitsTasks,
      final KeySerializingExecutor executorThatRunsTasks) throws Exception {

    runNTimesAndWaitForAllToComplete(numSimultaneous, executorThatSubmitsTasks,
        new ConcurrencyTestUtil.IndexedExceptionThrowingRunnable() {
          @Override
          public void run(int invocationIndex) throws Exception {
            WrappingKeySerializingExecutorTest.this.runSeriesOfTasksForOneKey(executorThatRunsTasks, keyNumber(invocationIndex));
          }
        }
    );
  }

  private String keyNumber(int i) {
    return "key" + String.valueOf(i);
  }

  private void runSeriesOfTasksForOneKey(KeySerializingExecutor keySerializingExecutor,
                                         String key) throws Exception {
    setNumberOfTasks(2);

    List<Integer> log = submitSeveralTasksAndBeginLoggingTheirInvocations(keySerializingExecutor, key);
    waitForTasksToFinish(keySerializingExecutor, key);
    assertThat(log, containsRecordOfEveryTask());
    assertThat(log, (Matcher<? super List<Integer>>) isInTheOrderTheTasksWereSubmitted());
  }

  private static void setNumberOfTasks(int n) {
    numTasks = n;
  }

  private void executeASingleKeyConcurrencyStressTest(int numCalls, ExecutorService executor)
      throws Exception {
    final KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(
        Executors.newSingleThreadExecutor());
    final List<Integer> taskResults = Collections.synchronizedList(new ArrayList<Integer>(numCalls));

    // The keySerializingExecutor can make no guarantee about the order in which tasks will
    // be completed if added with the same key from multiple threads; only that they will all be completed.
    runNTimesAndWaitForAllToComplete(numCalls, executor,
        new ConcurrencyTestUtil.ExceptionThrowingRunnable() {
          @Override
          public void run() throws Exception {
            keySerializingExecutor.submit("key", new CheckedSupplier<Object, Exception>() {
              @Override
              public Object get() throws Exception {
                taskResults.add(0);
                return 0;
              }
            });
          }
        });

    keySerializingExecutor.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
    assertThat(taskResults, hasSize(numCalls));
  }

  private void executeAShutdownIdempotencyStressTest(int numShutdownCalls,
                                                     ExecutorService shutdownCallingService) throws Exception {
    final KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(
        Executors.newSingleThreadExecutor());
    keySerializingExecutor.submit("key", new CheckedSupplier<Object, Exception>() {
      @Override
      public Object get() throws Exception {
        return null;
      }
    }).get();

    runNTimesAndWaitForAllToComplete(numShutdownCalls, shutdownCallingService,
        new ConcurrencyTestUtil.ExceptionThrowingRunnable() {
          @Override
          public void run() throws Exception {
            keySerializingExecutor.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
          }
        });
  }

  private void executeAShutdownAtomicityStressTest(final int numberOfSubmissions,
                                                   ExecutorService executor) throws Exception {
    final KeySerializingExecutor keySerializingExecutor = new WrappingKeySerializingExecutor(
        Executors.newSingleThreadExecutor());

    // Simply call shutdown interspersed with other submit calls and ensure there are no errors
    // However, it is nondeterministic which, if any, of the submits will go through.
    runNTimesAndWaitForAllToComplete(numberOfSubmissions, executor,
        new ConcurrencyTestUtil.IndexedExceptionThrowingRunnable() {
          @Override
          public void run(int invocationIndex) throws Exception {
            if (invocationIndex == numberOfSubmissions / 2) {
              keySerializingExecutor.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);
            } else {
              try {
                keySerializingExecutor.submit(WrappingKeySerializingExecutorTest.this.keyNumber(invocationIndex), new CheckedSupplier<Object, Exception>() {
                  @Override
                  public Object get() throws Exception {
                    return null;
                  }
                });
              } catch (RejectedExecutionException ignore) {
              }
            }
          }
        }
    );
  }

  private static Matcher<Collection<?>> containsRecordOfEveryTask() {
    return hasSize(numTasks * 2);
  }

  private static <T extends Comparable<T>> Matcher<List<T>> isInTheOrderTheTasksWereSubmitted() {
    return CollectionMatchers.isNondecreasing();
  }
}
