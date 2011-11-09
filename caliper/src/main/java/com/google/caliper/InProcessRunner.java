/*
 * Copyright (C) 2010 Google Inc.
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

package com.google.caliper;

import com.google.caliper.UserException.ExceptionFromUserCodeException;
import com.google.common.base.Supplier;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * Executes a benchmark in the current VM.
 */
final class InProcessRunner {

  public void run(String... args) {
    Arguments arguments = Arguments.parse(args);

    ScenarioSelection scenarioSelection = new ScenarioSelection(arguments);

    try {
      Measurer measurer = getMeasurer(arguments);
      List<Scenario> scenarios = scenarioSelection.select();
      // We only expect one scenario right now - if we have more, something has gone wrong.
      // This matters for things like reading the measurements. This is only done once, so if
      // multiple scenarios are executed, they will be ignored!
      if (scenarios.size() != 1) {
        throw new IllegalArgumentException("Invalid arguments to subprocess. Expected exactly one "
            + "scenario but got " + scenarios.size());
      }
      Scenario scenario = scenarios.get(0);

      System.out.println("starting " + scenario);
      MeasurementSet measurementSet = run(scenarioSelection, scenario, measurer);
      System.out.println(arguments.getMarker() + Json.measurementSetToJson(measurementSet));
    } catch (UserException e) {
      throw e;
    } catch (Exception e) {
      throw new ExceptionFromUserCodeException(e);
    }
  }

  public MeasurementSet run(final ScenarioSelection scenarioSelection, final Scenario scenario,
      Measurer measurer) throws Exception {
    Supplier<ConfiguredBenchmark> supplier = new Supplier<ConfiguredBenchmark>() {
      @Override public ConfiguredBenchmark get() {
        return scenarioSelection.createBenchmark(scenario);
      }
    };

    PrintStream out = System.out;
    PrintStream err = System.err;
    measurer.setLogStream(out);
    CountingPrintStream countedOut = new CountingPrintStream(out);
    CountingPrintStream countedErr = new CountingPrintStream(err);
    System.setOut(countedOut);
    System.setErr(countedErr);
    try {
      MeasurementSet measurementSet = measurer.run(supplier);
      if (measurementSet != null) {
        measurementSet = measurementSet.plusCharCounts(
            countedOut.getCount(), countedErr.getCount());
      }
      return measurementSet;
    } finally {
      System.setOut(out);
      System.setErr(err);
    }
  }

  private Measurer getMeasurer(Arguments arguments) {
    if (arguments.getMeasurementType() == MeasurementType.TIME) {
      return new TimeMeasurer(arguments.getWarmupMillis(), arguments.getRunMillis());
    } else if (arguments.getMeasurementType() == MeasurementType.INSTANCE) {
      return new InstancesAllocationMeasurer();
    } else if (arguments.getMeasurementType() == MeasurementType.MEMORY) {
      return new MemoryAllocationMeasurer();
    } else if (arguments.getMeasurementType() == MeasurementType.DEBUG) {
      return new DebugMeasurer(arguments.getDebugReps());
    } else {
      throw new IllegalArgumentException("unrecognized measurement type: "
          + arguments.getMeasurementType());
    }
  }

  public static void main(String... args) throws Exception {
    try {
      new InProcessRunner().run(args);
      System.exit(0); // user code may have leave non-daemon threads behind!
    } catch (UserException e) {
      e.display(); // TODO: send this to the host process
      System.out.println(LogConstants.CALIPER_LOG_PREFIX + LogConstants.SCENARIOS_FINISHED);
      System.exit(1);
    }
  }

  public PrintStream nullPrintStream() {
    return new PrintStream(new OutputStream() {
      public void write(int b) {}
    });
  }
}
