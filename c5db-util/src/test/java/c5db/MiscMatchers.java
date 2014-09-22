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

package c5db;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Matchers that don't belong in any other class, or generators or combinators of matchers.
 */
public class MiscMatchers {

  public static <T> Matcher<T> simpleMatcherForPredicate(Predicate<T> matches, Consumer<Description> describe) {
    return new TypeSafeMatcher<T>() {
      @Override
      protected boolean matchesSafely(T item) {
        return matches.test(item);
      }

      @Override
      public void describeTo(Description description) {
        describe.accept(description);
      }
    };
  }
}
