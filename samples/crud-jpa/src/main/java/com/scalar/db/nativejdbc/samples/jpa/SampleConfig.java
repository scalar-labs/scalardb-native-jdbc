package com.scalar.db.nativejdbc.samples.jpa;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.EntityManagerFactory;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Spring Data JPA and Hibernate over a {@link DataSource} on the driver's URL, as for the database
 * itself: Hibernate resolves its dialect from the connection's metadata, which the driver forwards
 * (design 4.6). Hibernate never creates or changes the tables, which ScalarDB and the driver's
 * setup own.
 */
@Configuration
@EnableJpaRepositories
@EnableTransactionManagement
@ComponentScan
public class SampleConfig {

  private final DataSource dataSource;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "the application's pool, handed over by Spring, is shared by design")
  public SampleConfig(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * The entity manager factory; {@code hibernate.default_schema}, or on MySQL and MariaDB, whose
   * databases Hibernate names as catalogs, {@code hibernate.default_catalog}, from the environment.
   */
  @Bean
  LocalContainerEntityManagerFactoryBean entityManagerFactory(Environment env) {
    LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
    emf.setDataSource(dataSource);
    emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    emf.setPackagesToScan(SampleConfig.class.getPackageName());
    Properties props = new Properties();
    props.setProperty("hibernate.hbm2ddl.auto", "none");
    for (String name : new String[] {"hibernate.default_schema", "hibernate.default_catalog"}) {
      String value = env.getProperty(name);
      if (value != null) {
        props.setProperty(name, value);
      }
    }
    emf.setJpaProperties(props);
    return emf;
  }

  @Bean
  PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
    return new JpaTransactionManager(emf);
  }
}
