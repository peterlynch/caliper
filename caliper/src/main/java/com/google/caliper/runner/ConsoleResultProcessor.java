/**
 * Copyright (C) 2009 Google Inc.
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

package com.google.caliper.runner;

import com.google.caliper.model.CaliperData;
import com.google.caliper.model.Instrument;
import com.google.caliper.model.Measurement;
import com.google.caliper.model.Result;
import com.google.caliper.model.Scenario;
import com.google.caliper.model.VM;
import com.google.caliper.util.LinearTranslation;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Table;
import com.google.common.primitives.Doubles;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Prints a report containing the tested values and the corresponding
 * measurements. Measurements are grouped by variable using indentation.
 * Alongside numeric values, quick-glance ascii art bar charts are printed.
 * Sample output (this may not represent the exact form that is produced):
 * <pre>
 *              benchmark          d     ns linear runtime
 * ConcatenationBenchmark 3.14159265   4397 0=======================
 * ConcatenationBenchmark       -0.0    223 0==============
 *     FormatterBenchmark 3.14159265  33999 0=============================
 *     FormatterBenchmark       -0.0  26399 0============================
 * </pre>
 */
final class ConsoleResultProcessor implements ResultProcessor {

  private static final int maxParamWidth = 30;
  private static final int barGraphWidth = 30;

  // TODO(schmoe): get the expected extremes from the instrument
  private static final int UNITS_FOR_SCORE_100 = 1;
  private static final int UNITS_FOR_SCORE_10 = 1000000000; // 1 s

  // TODO(schmoe): score shouldn't be specific to console-display
  private static final LinearTranslation scoreTranslation =
      new LinearTranslation(Math.log(UNITS_FOR_SCORE_10), 10, Math.log(UNITS_FOR_SCORE_100), 100);

  private final boolean printScore;

  // TODO(schmoe): these feel dirty - they assume a ConsoleResultProcessor is single-use, or at
  // least non-overlapping-use, which is only sorta necessary. Seems like handleResults should
  // create a one-per-call state object containing these fields and pass that around instead of
  // having these as member vars.
  private CaliperData data;
  // (scenario.localName, axisName) -> value
  private Table<String, String, String> scenarioLocalVars;
  private Map<String, ProcessedResult> processedResults;
  private List<Axis> sortedAxes;
  private ImmutableSortedSet<String> sortedScenarioNames;

  private double minValue;
  private double maxValue;

  ConsoleResultProcessor(boolean printScore) {
    this.printScore = printScore;
  }

  @Override public void handleResults(CaliperData data) {
    this.data = data;
    Map<String, VM> vms = Maps.uniqueIndex(data.vms, VM_LOCAL_NAME_FUNCTION);
    this.scenarioLocalVars = HashBasedTable.create();
    for (Scenario scenario : data.scenarios) {
      String localName = scenario.localName;
      scenarioLocalVars.put(localName, "benchmark", scenario.benchmarkMethodName);
      scenarioLocalVars.put(localName, "vm", vms.get(scenario.vmLocalName).vmName);
      for (Entry<String, String> entry : scenario.userParameters.entrySet()) {
        scenarioLocalVars.put(localName, entry.getKey(), entry.getValue());
      }
      for (Entry<String, String> entry : scenario.vmArguments.entrySet()) {
        scenarioLocalVars.put(localName, entry.getKey(), entry.getValue());
      }
    }

    for (Instrument instrument : data.instruments) {
      displayResults(instrument);
    }
  }

  private void displayResults(Instrument instrument) {
    System.out.printf("Results for %s:%n", instrument.className);

    processedResults = Maps.newHashMap();
    for (Result result : data.results) {
      if (instrument.localName.equals(result.instrumentLocalName)) {
        ProcessedResult existingResult = processedResults.get(result.scenarioLocalName);
        if (existingResult == null) {
          processedResults.put(result.scenarioLocalName, new ProcessedResult(result));
        } else {
          processedResults.put(result.scenarioLocalName, combineResults(existingResult, result));
        }
      }
    }

    double minOfMedians = Double.POSITIVE_INFINITY;
    double maxOfMedians = Double.NEGATIVE_INFINITY;

    for (ProcessedResult result : processedResults.values()) {
      minOfMedians = Math.min(minOfMedians, result.median);
      maxOfMedians = Math.max(maxOfMedians, result.median);
    }

    Multimap<String, String> scenarioVars = HashMultimap.create();
    for (Scenario scenario : data.scenarios) {
      // only include scenarios with data for this instrument
      if (processedResults.keySet().contains(scenario.localName)) {
        for (Entry<String, String> entry : scenarioLocalVars.row(scenario.localName).entrySet()) {
          scenarioVars.put(entry.getKey(), entry.getValue());
        }
      }
    }

    List<Axis> axes = Lists.newArrayList();
    for (Entry<String, Collection<String>> entry : scenarioVars.asMap().entrySet()) {
      Axis axis = new Axis(entry.getKey(), entry.getValue());
      axes.add(axis);
    }

    /*
     * Figure out how much influence each axis has on the measured value.
     * We sum the measurements taken with each value of each axis. For
     * axes that have influence on the measurement, the sums will differ
     * by value. If the axis has little influence, the sums will be similar
     * to one another and close to the overall average. We take the variance
     * across each axis' collection of sums. Higher variance implies higher
     * influence on the measured result.
     */
    double sumOfAllMeasurements = 0;
    for (ProcessedResult result : processedResults.values()) {
      sumOfAllMeasurements += result.median;
    }
    for (Axis axis : axes) {
      int numValues = axis.numberOfValues();
      double[] sumForValue = new double[numValues];
      for (Entry<String, ProcessedResult> entry : processedResults.entrySet()) {
        String scenarioLocalName = entry.getKey();
        ProcessedResult result = entry.getValue();
        sumForValue[axis.index(scenarioLocalName)] += result.median;
      }
      double mean = sumOfAllMeasurements / sumForValue.length;
      double variance = 0;
      for (double value : sumForValue) {
        double distance = value - mean;
        variance += distance * distance;
      }
      axis.variance = variance / numValues;
    }

    this.sortedAxes = new VarianceOrdering().reverse().sortedCopy(axes);
    this.sortedScenarioNames =
        ImmutableSortedSet.copyOf(new ByAxisOrdering(), processedResults.keySet());
    this.maxValue = maxOfMedians;
    this.minValue = minOfMedians;

    displayResults();
  }

  private ProcessedResult combineResults(ProcessedResult r1, Result r2) {
    Preconditions.checkArgument(r1.modelResult.instrumentLocalName.equals(r2.instrumentLocalName));
    Preconditions.checkArgument(r1.modelResult.scenarioLocalName.equals(r2.scenarioLocalName));
    r2.measurements = ImmutableList.<Measurement>builder()
        .addAll(r1.modelResult.measurements)
        .addAll(r2.measurements)
        .build();
    return new ProcessedResult(r2);
  }

  /**
   * A scenario variable and the set of values to which it has been assigned.
   */
  private class Axis {
    final String name;
    final ImmutableList<String> values;
    final int maxLength;
    double variance;

    Axis(String name, Collection<String> values) {
      this.name = name;
      this.values = ImmutableList.copyOf(values);
      Preconditions.checkArgument(!this.values.isEmpty());

      int maxLen = name.length();
      for (String value : values) {
        maxLen = Math.max(maxLen, value.length());
      }
      this.maxLength = Math.min(maxLen, maxParamWidth);
    }

    String get(String scenarioLocalName) {
      return scenarioLocalVars.get(scenarioLocalName, name);
    }

    int index(String scenarioLocalName) {
      return values.indexOf(get(scenarioLocalName));
    }

    int numberOfValues() {
      return values.size();
    }

    boolean isSingleton() {
      return values.size() == 1;
    }
  }

  /**
   * Orders the different axes by their variance. This results
   * in an appropriate grouping of output values.
   */
  private static class VarianceOrdering extends Ordering<Axis> {
    public int compare(Axis a, Axis b) {
      return Double.compare(a.variance, b.variance);
    }
  }

  /**
   * Orders scenarios by the axes.
   */
  private class ByAxisOrdering extends Ordering<String> {
    public int compare(String scenarioALocalName, String scenarioBLocalName) {
      for (Axis axis : sortedAxes) {
        int aValue = axis.values.indexOf(axis.get(scenarioALocalName));
        int bValue = axis.values.indexOf(axis.get(scenarioBLocalName));
        int diff = aValue - bValue;
        if (diff != 0) {
          return diff;
        }
      }
      return 0;
    }
  }

  void displayResults() {
    printValues();
    System.out.println();
    printSingletonAxes();
    printCharCounts();
  }

  private void printCharCounts() {
    // TODO(schmoe): The old version displayed a message if the benchmark wrote to stderr or stdout,
    // which included the #characters written to each one, and indicating that --debug would make
    // those visible. We don't currently know if the benchmark wrote any such messages; we should
    // tell the user if there were any messages, and provide a mechanism ("--debug"?) to let the
    // user see those messages.
  }

  /**
   * Prints a table of values.
   */
  private void printValues() {
    // header
    for (Axis axis : sortedAxes) {
      if (!axis.isSingleton()) {
        System.out.printf("%" + Math.min(axis.maxLength, maxParamWidth) + "s ", axis.name);
      }
    }
    // doesn't make sense to show graphs at all for 1
    // scenario, since it leads to vacuous graphs.
    boolean showGraphs = sortedScenarioNames.size() > 1;

    ProcessedResult firstResult = processedResults.values().iterator().next();
    String responseUnit = firstResult.responseUnit;
    String responseDesc = firstResult.responseDesc;

    int measurementLength = Math.max(10, responseUnit.length());
    System.out.printf("%" + measurementLength + "s", responseUnit);
    if (showGraphs) {
      System.out.print(" " + responseDesc);
    }
    System.out.println();

    double sumOfLogs = 0.0;

    String measurementPattern = "%" + measurementLength + ".3f";
    for (String scenarioLocalName : sortedScenarioNames) {
      ProcessedResult result = processedResults.get(scenarioLocalName);
      for (Axis axis : sortedAxes) {
        if (!axis.isSingleton()) {
          System.out.printf("%" + axis.maxLength + "s ",
              truncate(axis.get(scenarioLocalName), axis.maxLength));
        }
      }
      sumOfLogs += Math.log(result.median);

      System.out.printf(measurementPattern, result.median);

      if (showGraphs) {
        System.out.printf(" %s", barGraph(result.median));
      }
      System.out.println();
    }

    if (printScore) {
      // TODO(schmoe): move score computation to ResultSet
      // arithmetic mean of logs, aka log of geometric mean
      double meanLogUnits = sumOfLogs / processedResults.size();
      System.out.format("%nScore: %.3f%n", scoreTranslation.translate(meanLogUnits));
    }
  }

  /**
   * Prints axes with only one unique value.
   */
  private void printSingletonAxes() {
    for (Axis axis : sortedAxes) {
      if (axis.isSingleton()) {
        System.out.println(axis.name + ": " + Iterables.getOnlyElement(axis.values));
      }
    }
  }

  /**
   * Returns a string containing a bar of proportional width to the specified
   * value.
   */
  private String barGraph(double value) {
    if (this.minValue >= 0) {
      int graphLength = floor(value / maxValue * barGraphWidth);
      graphLength = Math.max(1, graphLength);
      graphLength = Math.min(barGraphWidth, graphLength);
      return Strings.repeat("=", graphLength);
    } else {
      // we want:    ========0
      //                =====0
      //                     0========
      int zeroIndex = floor((-minValue) * barGraphWidth / (maxValue - minValue));
      if (value < 0) {
        int barLength = ceil(value / minValue * zeroIndex);
        return Strings.repeat(" ", zeroIndex - barLength) + Strings.repeat("=", barLength) + "0";
      } else {
        int barLength = floor(value / maxValue * (barGraphWidth - zeroIndex));
        return Strings.repeat(" ", zeroIndex) + "0" + Strings.repeat("=", barLength);
      }
    }
  }

  @SuppressWarnings("NumericCastThatLosesPrecision")
  private static int floor(double d) {
    return (int) Math.floor(d);
  }

  @SuppressWarnings("NumericCastThatLosesPrecision")
  private static int ceil(double d) {
    return (int) Math.ceil(d);
  }

  private static String truncate(String s, int maxLength) {
    if (s.length() <= maxLength) {
      return s;
    } else {
      return s.substring(0, maxLength - 1) + "+";
    }
  }

  
  private static class ProcessedResult {
    private final Result modelResult;
    private final double[] values;
    private final double min;
    private final double max;
    private final double median;
    private final double mean;
    private final String responseUnit;
    private final String responseDesc;

    private ProcessedResult(Result modelResult) {
      this.modelResult = modelResult;
      values = getValues(modelResult.measurements);
      min = Doubles.min(values);
      max = Doubles.max(values);
      median = computeMedian(values);
      mean = computeMean(values);
      Measurement firstMeasurement = modelResult.measurements.get(0);
      responseUnit = firstMeasurement.unit;
      responseDesc = firstMeasurement.description;
    }

    private static double[] getValues(Collection<Measurement> measurements) {
      double[] values = new double[measurements.size()];
      int i = 0;
      for (Measurement measurement : measurements) {
        values[i] = measurement.value / measurement.weight;
        i++;
      }
      return values;
    }

    // TODO(schmoe): consider copying com.google.math.Sample into caliper.util
    private static double computeMedian(double[] values) {
      double[] sortedValues = values.clone();
      Arrays.sort(sortedValues);
      if (sortedValues.length % 2 == 1) {
        return sortedValues[sortedValues.length / 2];
      } else {
        double high = sortedValues[sortedValues.length / 2];
        double low = sortedValues[(sortedValues.length / 2) - 1];
        return (low + high) / 2;
      }
    }

    private static double computeMean(double[] values) {
      double sum = 0;
      for (double value : values) {
        sum += value;
      }
      return sum / values.length;
    }
  }

  private static final Function<VM, String> VM_LOCAL_NAME_FUNCTION =
      new Function<VM, String>() {
        @Override public String apply(VM vm) {
          return vm.localName;
        }
      };
}
