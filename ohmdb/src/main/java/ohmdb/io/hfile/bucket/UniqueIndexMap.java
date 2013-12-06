/*
 * Copyright (C) 2013  Ohm Data
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  This file incorporates work covered by the following copyright and
 *  permission notice:
 */

/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package ohmdb.io.hfile.bucket;


import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Map from type T to int and vice-versa. Used for reducing bit field item
 * counts.
 */
public final class UniqueIndexMap<T> implements Serializable {
  private static final long serialVersionUID = -1145635738654002342L;

  ConcurrentHashMap<T, Integer> mForwardMap = new ConcurrentHashMap<T, Integer>();
  ConcurrentHashMap<Integer, T> mReverseMap = new ConcurrentHashMap<Integer, T>();
  AtomicInteger mIndex = new AtomicInteger(0);

  // Map a length to an index. If we can't, allocate a new mapping. We might
  // race here and get two entries with the same deserialiser. This is fine.
  int map(T parameter) {
    Integer ret = mForwardMap.get(parameter);
    if (ret != null) return ret.intValue();
    int nexti = mIndex.incrementAndGet();
    assert (nexti < Short.MAX_VALUE);
    mForwardMap.put(parameter, nexti);
    mReverseMap.put(nexti, parameter);
    return nexti;
  }

  T unmap(int leni) {
    Integer len = Integer.valueOf(leni);
    assert mReverseMap.containsKey(len);
    return mReverseMap.get(len);
  }
}
