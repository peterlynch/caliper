/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.caliper.worker;

import com.google.caliper.api.Benchmark;
import com.google.caliper.model.Measurement;

import java.util.Collection;
import java.util.Map;

public interface Worker {
  Collection<Measurement> measure(Benchmark benchmark, String methodName,
      Map<String, String> options, WorkerEventLog log) throws Exception;
}
