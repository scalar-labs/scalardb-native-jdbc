package com.scalar.db.nativejdbc.samples.springdata;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Spring Data JDBC over a {@link DataSource} on the driver's URL, as for the database itself: the
 * dialect is resolved from the connection's metadata, which the driver forwards (design 4.6).
 */
@Configuration
@EnableJdbcRepositories
@EnableTransactionManagement
@ComponentScan
public class SampleConfig extends AbstractJdbcConfiguration {

  private final DataSource dataSource;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "the application's pool, handed over by Spring, is shared by design")
  public SampleConfig(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Bean
  NamedParameterJdbcOperations namedParameterJdbcOperations() {
    return new NamedParameterJdbcTemplate(dataSource);
  }

  @Bean
  PlatformTransactionManager transactionManager() {
    return new DataSourceTransactionManager(dataSource);
  }
}
