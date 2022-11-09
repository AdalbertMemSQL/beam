/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.singlestore;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.apache.beam.sdk.coders.ListCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.values.PCollection;
import org.apache.commons.dbcp2.DelegatingStatement;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Instant;

@AutoValue
public abstract class Write<T> extends PTransform<PCollection<T>, PCollection<Integer>> {

  private static final int DEFAULT_BATCH_SIZE = 100000;
  private static final int BUFFER_SIZE = 524288;

  abstract @Nullable DataSourceConfiguration getDataSourceConfiguration();

  abstract @Nullable String getTable();

  abstract @Nullable Integer getBatchSize();

  abstract @Nullable UserDataMapper<T> getUserDataMapper();

  abstract Builder<T> toBuilder();

  @AutoValue.Builder
  abstract static class Builder<T> {
    abstract Builder<T> setDataSourceConfiguration(DataSourceConfiguration dataSourceConfiguration);

    abstract Builder<T> setTable(String table);

    abstract Builder<T> setBatchSize(Integer batchSize);

    abstract Builder<T> setUserDataMapper(UserDataMapper<T> userDataMapper);

    abstract Write<T> build();
  }

  public Write<T> withDataSourceConfiguration(DataSourceConfiguration config) {
    checkNotNull(config, "dataSourceConfiguration can not be null");
    return toBuilder().setDataSourceConfiguration(config).build();
  }

  public Write<T> withTable(String table) {
    checkNotNull(table, "table can not be null");
    return toBuilder().setTable(table).build();
  }

  public Write<T> withUserDataMapper(UserDataMapper<T> userDataMapper) {
    checkNotNull(userDataMapper, "userDataMapper can not be null");
    return toBuilder().setUserDataMapper(userDataMapper).build();
  }

  /**
   * Provide a maximum number of rows that is written by one SQL statement. Default is 100000.
   *
   * @param batchSize maximum number of rows that is written by one SQL statement
   */
  public Write<T> withBatchSize(Integer batchSize) {
    checkNotNull(batchSize, "batchSize can not be null");
    return toBuilder().setBatchSize(batchSize).build();
  }

  @Override
  public PCollection<Integer> expand(PCollection<T> input) {
    DataSourceConfiguration dataSourceConfiguration =
        SingleStoreUtil.getRequiredArgument(
            getDataSourceConfiguration(), "withDataSourceConfiguration() is required");
    String table = SingleStoreUtil.getRequiredArgument(getTable(), "withTable() is required");
    UserDataMapper<T> userDataMapper =
        SingleStoreUtil.getRequiredArgument(
            getUserDataMapper(), "withUserDataMapper() is required");
    int batchSize = SingleStoreUtil.getArgumentWithDefault(getBatchSize(), DEFAULT_BATCH_SIZE);
    checkArgument(batchSize > 0, "batchSize should be greater then 0");

    return input
        .apply(
            ParDo.of(
                new DoFn<T, List<String>>() {
                  @ProcessElement
                  public void processElement(ProcessContext context) throws Exception {
                    context.output(userDataMapper.mapRow(context.element()));
                  }
                }))
        .setCoder(ListCoder.of(StringUtf8Coder.of()))
        .apply(ParDo.of(new BatchFn<>(batchSize)))
        .apply(ParDo.of(new WriteFn(dataSourceConfiguration, table)));
  }

  private static class BatchFn<ParameterT> extends DoFn<ParameterT, Iterable<ParameterT>> {
    List<ParameterT> batch = new ArrayList<>();
    int batchSize;

    BatchFn(int batchSize) {
      this.batchSize = batchSize;
    }

    @ProcessElement
    public void processElement(ProcessContext context) {
      batch.add(context.element());
      if (batch.size() >= batchSize) {
        context.output(batch);
        batch = new ArrayList<>();
      }
    }

    @FinishBundle
    public void finish(FinishBundleContext context) {
      if (batch.size() > 0) {
        context.output(batch, Instant.now(), GlobalWindow.INSTANCE);
        batch = new ArrayList<>();
      }
    }
  }

  private static class WriteFn extends DoFn<Iterable<List<String>>, Integer> {
    DataSourceConfiguration dataSourceConfiguration;
    String table;

    WriteFn(DataSourceConfiguration dataSourceConfiguration, String table) {
      this.dataSourceConfiguration = dataSourceConfiguration;
      this.table = table;
    }

    @ProcessElement
    public void processElement(ProcessContext context) throws Exception {
      DataSource dataSource = dataSourceConfiguration.getDataSource();

      Connection conn = dataSource.getConnection();
      try {
        Statement stmt = conn.createStatement();
        try (PipedOutputStream baseStream = new PipedOutputStream();
            InputStream inputStream = new PipedInputStream(baseStream, BUFFER_SIZE)) {
          ((com.singlestore.jdbc.Statement) ((DelegatingStatement) stmt).getInnermostDelegate())
              .setNextLocalInfileInputStream(inputStream);

          final Exception[] writeException = new Exception[1];

          Thread dataWritingThread =
              new Thread(
                  new Runnable() {
                    @Override
                    public void run() {
                      try {
                        Iterable<List<String>> rows = context.element();
                        for (List<String> row : rows) {
                          for (int i = 0; i < row.size(); i++) {
                            String cell = row.get(i);
                            if (cell.indexOf('\\') != -1) {
                              cell = cell.replace("\\", "\\\\");
                            }
                            if (cell.indexOf('\n') != -1) {
                              cell = cell.replace("\n", "\\n");
                            }
                            if (cell.indexOf('\t') != -1) {
                              cell = cell.replace("\t", "\\t");
                            }

                            baseStream.write(cell.getBytes(StandardCharsets.UTF_8));
                            if (i + 1 == row.size()) {
                              baseStream.write('\n');
                            } else {
                              baseStream.write('\t');
                            }
                          }
                        }

                        baseStream.close();
                      } catch (IOException e) {
                        writeException[0] = e;
                      }
                    }
                  });

          dataWritingThread.start();
          Integer rows =
              stmt.executeUpdate(
                  String.format(
                      "LOAD DATA LOCAL INFILE '###.tsv' INTO TABLE %s",
                      SingleStoreUtil.escapeIdentifier(table)));

          context.output(rows);
          dataWritingThread.join();

          if (writeException[0] != null) {
            throw writeException[0];
          }
        } finally {
          stmt.close();
        }
      } finally {
        conn.close();
      }
    }
  }

  @Override
  public void populateDisplayData(DisplayData.Builder builder) {
    super.populateDisplayData(builder);

    DataSourceConfiguration.populateDisplayData(getDataSourceConfiguration(), builder);
    builder.addIfNotNull(DisplayData.item("table", getTable()));
    builder.addIfNotNull(DisplayData.item("batchSize", getBatchSize()));
    builder.addIfNotNull(
        DisplayData.item(
            "userDataMapper", SingleStoreUtil.getClassNameOrNull(getUserDataMapper())));
  }
}
