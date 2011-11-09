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

public final class WorkerEventLog {

  // temporary dumb messages.

  public void notifyWarmupPhaseStarting() {
    System.out.println("Warmup starting.");
  }

  public void notifyMeasurementPhaseStarting() {
    System.out.println("Measurement phase starting.");
  }

  public void notifyMeasurementStarting() {
    System.out.println("About to measure.");
  }

  public void notifyMeasurementEnding(double value) {
    System.out.println("I got a result: " + value);
  }

}
