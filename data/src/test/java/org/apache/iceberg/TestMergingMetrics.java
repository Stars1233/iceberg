/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.RandomGenericData;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.NaNUtil;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(ParameterizedTestExtension.class)
public abstract class TestMergingMetrics<T> {

  // all supported fields, except for UUID which is on deprecation path: see
  // https://github.com/apache/iceberg/pull/1611
  // as well as Types.TimeType and Types.TimestampType.withoutZone as both are not supported by
  // Spark
  protected static final Types.NestedField ID_FIELD = required(1, "id", Types.IntegerType.get());
  protected static final Types.NestedField DATA_FIELD = optional(2, "data", Types.StringType.get());
  protected static final Types.NestedField FLOAT_FIELD =
      required(3, "float", Types.FloatType.get());
  protected static final Types.NestedField DOUBLE_FIELD =
      optional(4, "double", Types.DoubleType.get());
  protected static final Types.NestedField DECIMAL_FIELD =
      optional(5, "decimal", Types.DecimalType.of(5, 3));
  protected static final Types.NestedField FIXED_FIELD =
      optional(7, "fixed", Types.FixedType.ofLength(4));
  protected static final Types.NestedField BINARY_FIELD =
      optional(8, "binary", Types.BinaryType.get());
  protected static final Types.NestedField FLOAT_LIST =
      optional(9, "floatlist", Types.ListType.ofRequired(10, Types.FloatType.get()));
  protected static final Types.NestedField LONG_FIELD = optional(11, "long", Types.LongType.get());

  protected static final Types.NestedField MAP_FIELD_1 =
      optional(
          17,
          "map1",
          Types.MapType.ofOptional(18, 19, Types.FloatType.get(), Types.StringType.get()));
  protected static final Types.NestedField MAP_FIELD_2 =
      optional(
          20,
          "map2",
          Types.MapType.ofOptional(21, 22, Types.IntegerType.get(), Types.DoubleType.get()));
  protected static final Types.NestedField STRUCT_FIELD =
      optional(
          23,
          "structField",
          Types.StructType.of(
              required(24, "booleanField", Types.BooleanType.get()),
              optional(25, "date", Types.DateType.get()),
              optional(27, "timestamp", Types.TimestampType.withZone())));

  private static final Map<Types.NestedField, Integer> FIELDS_WITH_NAN_COUNT_TO_ID =
      ImmutableMap.of(FLOAT_FIELD, 3, DOUBLE_FIELD, 4);

  // create a schema with all supported fields
  protected static final Schema SCHEMA =
      new Schema(
          ID_FIELD,
          DATA_FIELD,
          FLOAT_FIELD,
          DOUBLE_FIELD,
          DECIMAL_FIELD,
          FIXED_FIELD,
          BINARY_FIELD,
          FLOAT_LIST,
          LONG_FIELD,
          MAP_FIELD_1,
          MAP_FIELD_2,
          STRUCT_FIELD);

  protected abstract FileAppender<T> writeAndGetAppender(List<Record> records) throws Exception;

  @Parameters(name = "fileFormat = {0}")
  public static List<Object> parameters() {
    return Arrays.asList(FileFormat.PARQUET, FileFormat.ORC);
  }

  @Parameter protected FileFormat fileFormat;
  @TempDir protected File tempDir;

  @TestTemplate
  public void verifySingleRecordMetric() throws Exception {
    Record record = GenericRecord.create(SCHEMA);
    record.setField(ID_FIELD.name(), 3);
    record.setField(FLOAT_FIELD.name(), Float.NaN); // FLOAT_FIELD - 1
    record.setField(DOUBLE_FIELD.name(), Double.NaN); // DOUBLE_FIELD - 1
    record.setField(
        FLOAT_LIST.name(),
        ImmutableList.of(3.3F, 2.8F, Float.NaN, -25.1F, Float.NaN)); // FLOAT_LIST - 2
    record.setField(
        MAP_FIELD_1.name(), ImmutableMap.of(Float.NaN, "a", 0F, "b")); // MAP_FIELD_1 - 1
    record.setField(
        MAP_FIELD_2.name(),
        ImmutableMap.of(
            0, 0D, 1, Double.NaN, 2, 2D, 3, Double.NaN, 4, Double.NaN)); // MAP_FIELD_2 - 3

    FileAppender<T> appender = writeAndGetAppender(ImmutableList.of(record));
    Metrics metrics = appender.metrics();
    Map<Integer, Long> nanValueCount = metrics.nanValueCounts();
    Map<Integer, ByteBuffer> upperBounds = metrics.upperBounds();
    Map<Integer, ByteBuffer> lowerBounds = metrics.lowerBounds();

    assertThat(nanValueCount).hasSize(2);
    assertNaNCountMatch(1L, nanValueCount, FLOAT_FIELD);
    assertNaNCountMatch(1L, nanValueCount, DOUBLE_FIELD);

    assertThat(upperBounds).hasSize(1);
    assertBoundValueMatch(3, upperBounds, ID_FIELD);

    assertThat(lowerBounds).hasSize(1);
    assertBoundValueMatch(3, lowerBounds, ID_FIELD);
  }

  @TestTemplate
  public void verifyRandomlyGeneratedRecordsMetric() throws Exception {
    // too big of the record count will more likely to make all upper/lower bounds +/-infinity,
    // which makes the tests easier to pass
    List<Record> recordList = RandomGenericData.generate(SCHEMA, 5, 250L);
    FileAppender<T> appender = writeAndGetAppender(recordList);

    Map<Types.NestedField, AtomicReference<Number>> expectedUpperBounds = Maps.newHashMap();
    Map<Types.NestedField, AtomicReference<Number>> expectedLowerBounds = Maps.newHashMap();
    Map<Types.NestedField, AtomicLong> expectedNaNCount = Maps.newHashMap();

    populateExpectedValues(recordList, expectedUpperBounds, expectedLowerBounds, expectedNaNCount);

    Metrics metrics = appender.metrics();
    expectedUpperBounds.forEach(
        (key, value) -> assertBoundValueMatch(value.get(), metrics.upperBounds(), key));
    expectedLowerBounds.forEach(
        (key, value) -> assertBoundValueMatch(value.get(), metrics.lowerBounds(), key));
    expectedNaNCount.forEach(
        (key, value) -> assertNaNCountMatch(value.get(), metrics.nanValueCounts(), key));

    SCHEMA.columns().stream()
        .filter(column -> !FIELDS_WITH_NAN_COUNT_TO_ID.containsKey(column))
        .map(Types.NestedField::fieldId)
        .forEach(
            id ->
                assertThat(metrics.nanValueCounts().get(id))
                    .as("NaN count for field %s should be null")
                    .isNull());
  }

  private void assertNaNCountMatch(
      Long expected, Map<Integer, Long> nanValueCount, Types.NestedField field) {
    assertThat(nanValueCount)
        .as(String.format("NaN count for field %s does not match expected", field.name()))
        .containsEntry(field.fieldId(), expected);
  }

  private void assertBoundValueMatch(
      Number expected, Map<Integer, ByteBuffer> boundMap, Types.NestedField field) {
    ByteBuffer byteBuffer = boundMap.get(field.fieldId());
    Type type = SCHEMA.findType(field.fieldId());
    assertThat(byteBuffer == null ? null : Conversions.<T>fromByteBuffer(type, byteBuffer))
        .as(String.format("Bound value for field %s must match", field.name()))
        .isEqualTo(expected);
  }

  private void populateExpectedValues(
      List<Record> records,
      Map<Types.NestedField, AtomicReference<Number>> upperBounds,
      Map<Types.NestedField, AtomicReference<Number>> lowerBounds,
      Map<Types.NestedField, AtomicLong> expectedNaNCount) {
    for (Types.NestedField field : FIELDS_WITH_NAN_COUNT_TO_ID.keySet()) {
      expectedNaNCount.put(field, new AtomicLong(0));
    }

    for (Record record : records) {
      updateExpectedValuePerRecord(
          upperBounds,
          lowerBounds,
          expectedNaNCount,
          FLOAT_FIELD,
          (Float) record.getField(FLOAT_FIELD.name()));
      updateExpectedValuePerRecord(
          upperBounds,
          lowerBounds,
          expectedNaNCount,
          DOUBLE_FIELD,
          (Double) record.getField(DOUBLE_FIELD.name()));
    }
  }

  private void updateExpectedValuePerRecord(
      Map<Types.NestedField, AtomicReference<Number>> upperBounds,
      Map<Types.NestedField, AtomicReference<Number>> lowerBounds,
      Map<Types.NestedField, AtomicLong> expectedNaNCount,
      Types.NestedField key,
      Number val) {
    if (NaNUtil.isNaN(val)) {
      expectedNaNCount.get(key).incrementAndGet();
    } else if (val != null) {
      updateBound(key, val, upperBounds, true);
      updateBound(key, val, lowerBounds, false);
    }
  }

  private void updateBound(
      Types.NestedField key,
      Number val,
      Map<Types.NestedField, AtomicReference<Number>> bounds,
      boolean isMax) {
    bounds
        .computeIfAbsent(key, k -> new AtomicReference<>(val))
        .updateAndGet(old -> getMinOrMax(old, val, isMax));
  }

  private Number getMinOrMax(Number val1, Number val2, boolean isMax) {
    if (val1 instanceof Double) {
      return isMax
          ? Double.max((Double) val1, (Double) val2)
          : Double.min((Double) val1, (Double) val2);
    } else {
      return isMax ? Float.max((Float) val1, (Float) val2) : Float.min((Float) val1, (Float) val2);
    }
  }
}
