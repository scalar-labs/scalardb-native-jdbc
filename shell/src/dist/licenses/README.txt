Third-party software in lib/, each unmodified, under the licence in this directory.

lib/ jar                  Licence                                            File
postgresql                BSD-2-Clause                                       postgresql-LICENSE.txt
checker-qual              MIT (a dependency of postgresql)                   checker-qual-LICENSE.txt
mariadb-java-client       LGPL-2.1-or-later                                  mariadb-java-client-LICENSE.txt
mssql-jdbc                MIT                                                mssql-jdbc-LICENSE.txt
ojdbc11                   Oracle Free Distribution, Hosting, and Use Terms   ojdbc11-LICENSE.txt
jline-*                   BSD-3-Clause                                       jline-LICENSE.txt
picocli                   Apache-2.0                                         picocli-LICENSE.txt
jsqlparser                Apache-2.0 (dual-licensed with LGPL-2.1)           jsqlparser-LICENSE.txt
slf4j-api, slf4j-nop      MIT                                                slf4j-api-LICENSE.txt

MariaDB Connector/J is a separate, unmodified jar; you may replace it with another version, including
one you have modified. Its source code is at https://github.com/mariadb-corporation/mariadb-connector-j
and on Maven Central as org.mariadb.jdbc:mariadb-java-client, classifier "sources".

MySQL Connector/J is not included. To use it instead of MariaDB Connector/J for MySQL, put its jar in
lib/.
