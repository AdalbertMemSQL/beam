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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.mock.SerializableMode.ACROSS_CLASSLOADERS;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.GenerateSequence;
import org.apache.beam.sdk.io.common.TestRow;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.commons.dbcp2.DelegatingStatement;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testcontainers.shaded.com.google.common.base.Splitter;
import org.testcontainers.shaded.com.google.common.collect.Lists;

/** Test Write. */
@RunWith(JUnit4.class)
public class WriteTest {
  @Rule public final transient TestPipeline pipeline = TestPipeline.create();
  public final transient Pipeline pipelineForErrorChecks = Pipeline.create();

  private static DataSourceConfiguration dataSourceConfiguration;

  private static final int EXPECTED_ROW_COUNT = 1000;

  private static final List<TestRow> writtenRows =
      Collections.synchronizedList(new ArrayList<TestRow>());

  private static class SetInputStream implements Serializable, Answer<Void> {
    InputStream inputStream;

    @Override
    public Void answer(InvocationOnMock invocation) {
      inputStream = invocation.getArgument(0, InputStream.class);
      return null;
    }

    public InputStream getInputStream() {
      return inputStream;
    }
  }

  private static class ExecuteUpdate implements Serializable, Answer<Integer> {
    SetInputStream inputStreamSetter;

    private static AtomicInteger executionsNumber = new AtomicInteger(0);

    ExecuteUpdate(SetInputStream inputStreamSetter) {
      this.inputStreamSetter = inputStreamSetter;
    }

    @Override
    public Integer answer(InvocationOnMock invocation) {
      executionsNumber.incrementAndGet();
      InputStream s = inputStreamSetter.getInputStream();
      StringBuilder csvBuilder = new StringBuilder();
      byte[] b = new byte[100];

      try {
        while (true) {
          int len = s.read(b);
          if (len == -1) {
            break;
          }
          for (int i = 0; i < len; i++) {
            csvBuilder.append((char) b[i]);
          }
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      List<String> rows = Splitter.on('\n').omitEmptyStrings().splitToList(csvBuilder.toString());
      for (String row : rows) {
        List<String> values = Splitter.on('\t').splitToList(row);
        Integer id = Integer.valueOf(values.get(0));
        String name = values.get(1);

        writtenRows.add(TestRow.create(id, name));
      }
      return 1;
    }

    public static int getExecutionsNumber() {
      return executionsNumber.get();
    }

    public static void resetExecutionsNumber() {
      executionsNumber = new AtomicInteger(0);
    }
  }

  void checkRows() {
    assertEquals(
        new HashSet<>(Lists.newArrayList(TestRow.getExpectedValues(0, EXPECTED_ROW_COUNT))),
        new HashSet<>(writtenRows));
  }

  @After
  public void cleanup() {
    ExecuteUpdate.resetExecutionsNumber();
    writtenRows.clear();
  }

  private static class GetDataSource implements Serializable, Answer<DataSource> {
    @Override
    public DataSource answer(InvocationOnMock invocation) throws SQLException {
      com.singlestore.jdbc.Statement stmt =
          Mockito.mock(
              com.singlestore.jdbc.Statement.class,
              Mockito.withSettings().serializable(ACROSS_CLASSLOADERS));
      SetInputStream inputStreamSetter = new SetInputStream();
      Mockito.doAnswer(inputStreamSetter).when(stmt).setNextLocalInfileInputStream(Mockito.any());

      DelegatingStatement delStmt =
          Mockito.mock(
              DelegatingStatement.class, Mockito.withSettings().serializable(ACROSS_CLASSLOADERS));
      Mockito.when(delStmt.getInnermostDelegate()).thenReturn(stmt);
      Mockito.doAnswer(new ExecuteUpdate(inputStreamSetter))
          .when(delStmt)
          .executeUpdate("LOAD DATA LOCAL INFILE '###.tsv' INTO TABLE `t`");

      Connection conn =
          Mockito.mock(Connection.class, Mockito.withSettings().serializable(ACROSS_CLASSLOADERS));
      Mockito.when(conn.createStatement()).thenReturn(delStmt);

      DataSource dataSource =
          Mockito.mock(DataSource.class, Mockito.withSettings().serializable(ACROSS_CLASSLOADERS));
      Mockito.when(dataSource.getConnection()).thenReturn(conn);

      return dataSource;
    }
  }

  @Before
  public void init() throws SQLException {

    dataSourceConfiguration =
        Mockito.mock(
            TestHelper.MockDataSourceConfiguration.class, Mockito.withSettings().serializable());

    Mockito.doAnswer(new GetDataSource()).when(dataSourceConfiguration).getDataSource();
  }

  @Test
  public void testWrite() {
    int batchSize = EXPECTED_ROW_COUNT / 3 + 1;

    pipeline
        .apply(GenerateSequence.from(0).to(EXPECTED_ROW_COUNT))
        .apply(ParDo.of(new TestRow.DeterministicallyConstructTestRowFn()))
        .apply(
            SingleStoreIO.<TestRow>write()
                .withDataSourceConfiguration(dataSourceConfiguration)
                .withTable("t")
                .withUserDataMapper(new TestHelper.TestUserDataMapper())
                .withBatchSize(batchSize));

    pipeline.run();

    checkRows();
  }

  @Test
  public void testWriteSmallBatchSize() {
    pipeline
        .apply(GenerateSequence.from(0).to(EXPECTED_ROW_COUNT))
        .apply(ParDo.of(new TestRow.DeterministicallyConstructTestRowFn()))
        .apply(
            SingleStoreIO.<TestRow>write()
                .withDataSourceConfiguration(dataSourceConfiguration)
                .withTable("t")
                .withUserDataMapper(new TestHelper.TestUserDataMapper())
                .withBatchSize(1));

    pipeline.run();

    assertEquals(EXPECTED_ROW_COUNT, ExecuteUpdate.getExecutionsNumber());
    checkRows();
  }

  @Test
  public void testWriteBigBatchSize() {
    pipeline
        .apply(GenerateSequence.from(0).to(EXPECTED_ROW_COUNT))
        .apply(ParDo.of(new TestRow.DeterministicallyConstructTestRowFn()))
        .apply(
            SingleStoreIO.<TestRow>write()
                .withDataSourceConfiguration(dataSourceConfiguration)
                .withTable("t")
                .withUserDataMapper(new TestHelper.TestUserDataMapper())
                .withBatchSize(EXPECTED_ROW_COUNT));

    pipeline.run();
    checkRows();
  }

  @Test
  public void testWriteInvalidBatchSize() {
    assertThrows(
        "batchSize should be greater then 0",
        IllegalArgumentException.class,
        () ->
            pipelineForErrorChecks
                .apply(GenerateSequence.from(0).to(EXPECTED_ROW_COUNT))
                .apply(ParDo.of(new TestRow.DeterministicallyConstructTestRowFn()))
                .apply(
                    SingleStoreIO.<TestRow>write()
                        .withDataSourceConfiguration(dataSourceConfiguration)
                        .withTable("t")
                        .withUserDataMapper(new TestHelper.TestUserDataMapper())
                        .withBatchSize(0)));
  }
}
