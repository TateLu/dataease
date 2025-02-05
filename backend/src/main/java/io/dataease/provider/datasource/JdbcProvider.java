package io.dataease.provider.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.google.gson.Gson;
import io.dataease.commons.utils.LogUtil;
import io.dataease.dto.datasource.*;
import io.dataease.exception.DataEaseException;
import io.dataease.i18n.Translator;
import io.dataease.plugins.common.base.domain.Datasource;
import io.dataease.plugins.common.base.domain.DeDriver;
import io.dataease.plugins.common.base.mapper.DeDriverMapper;
import io.dataease.plugins.common.constants.DatasourceTypes;
import io.dataease.plugins.common.constants.datasource.MySQLConstants;
import io.dataease.plugins.common.dto.datasource.TableField;
import io.dataease.plugins.common.request.datasource.DatasourceRequest;
import io.dataease.plugins.datasource.entity.JdbcConfiguration;
import io.dataease.plugins.datasource.provider.DefaultJdbcProvider;
import io.dataease.plugins.datasource.provider.ExtendedJdbcClassLoader;
import io.dataease.plugins.datasource.query.QueryProvider;
import io.dataease.provider.ProviderFactory;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

@Service("jdbc")
public class JdbcProvider extends DefaultJdbcProvider {


    @Resource
    private DeDriverMapper deDriverMapper;

    @Override
    public boolean isUseDatasourcePool() {
        return true;
    }

    @Override
    public String getType() {
        return "built-in";
    }
    /**
     * 增加缓存机制 key 由 'provider_sql_' dsr.datasource.id dsr.table dsr.query共4部分组成，命中则使用缓存直接返回不再执行sql逻辑
     * @param dsr
     * @return
     * @throws Exception
     */

    /**
     * 这里使用声明式缓存不是很妥当
     * 改为chartViewService中使用编程式缓存
     *
     * @Cacheable( value = JdbcConstants.JDBC_PROVIDER_KEY,
     * key = "'provider_sql_' + #dsr.datasource.id + '_' + #dsr.table + '_' + #dsr.query",
     * condition = "#dsr.pageSize == null || #dsr.pageSize == 0L"
     * )
     */

    public void exec(DatasourceRequest datasourceRequest) throws Exception {
        JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
        int queryTimeout = jdbcConfiguration.getQueryTimeout() > 0 ? jdbcConfiguration.getQueryTimeout() : 0;
        try (Connection connection = getConnectionFromPool(datasourceRequest); Statement stat = getStatement(connection, queryTimeout)) {
            Boolean result = stat.execute(datasourceRequest.getQuery());
        } catch (SQLException e) {
            DataEaseException.throwException(e);
        } catch (Exception e) {
            DataEaseException.throwException(e);
        }
    }


    @Override
    public List<TableField> getTableFields(DatasourceRequest datasourceRequest) throws Exception {
        if (datasourceRequest.getDatasource().getType().equalsIgnoreCase("mongo")) {
            datasourceRequest.setQuery("select * from " + datasourceRequest.getTable());
            return fetchResultField(datasourceRequest);
        }
        List<TableField> list = new LinkedList<>();
        try (Connection connection = getConnectionFromPool(datasourceRequest)) {
            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase("oracle")) {
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                if (isDefaultClassLoader(oracleConfiguration.getCustomDriver())) {
                    Method setRemarksReporting = extendedJdbcClassLoader.loadClass("oracle.jdbc.driver.OracleConnection").getMethod("setRemarksReporting", boolean.class);
                    setRemarksReporting.invoke(((DruidPooledConnection) connection).getConnection(), true);
                }
            }
            DatabaseMetaData databaseMetaData = connection.getMetaData();
            String tableNamePattern = datasourceRequest.getTable();
            //distinguish different driver version
            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.mysql.name())) {
                if (databaseMetaData.getDriverMajorVersion() < 8) {
                    tableNamePattern = String.format(MySQLConstants.KEYWORD_TABLE, tableNamePattern);
                }
            }
            ResultSet resultSet = databaseMetaData.getColumns(null, "%", tableNamePattern, "%");
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                String database;
                String schema = resultSet.getString("TABLE_SCHEM");
                if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.pg.name())
                        || datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.ck.name())
                        || datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.impala.name())) {
                    database = resultSet.getString("TABLE_SCHEM");
                } else {
                    database = resultSet.getString("TABLE_CAT");
                }
                if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.pg.name())) {
                    if (tableName.equals(datasourceRequest.getTable()) && database.equalsIgnoreCase(getDsSchema(datasourceRequest))) {
                        TableField tableField = getTableFiled(resultSet, datasourceRequest);
                        list.add(tableField);
                    }
                } else if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.sqlServer.name())) {
                    if (tableName.equals(datasourceRequest.getTable()) && database.equalsIgnoreCase(getDatabase(datasourceRequest)) && schema.equalsIgnoreCase(getDsSchema(datasourceRequest))) {
                        TableField tableField = getTableFiled(resultSet, datasourceRequest);
                        list.add(tableField);
                    }
                } else {
                    if (database != null) {
                        if (tableName.equals(datasourceRequest.getTable()) && database.equalsIgnoreCase(getDatabase(datasourceRequest))) {
                            TableField tableField = getTableFiled(resultSet, datasourceRequest);
                            list.add(tableField);
                        }
                    } else {
                        if (tableName.equals(datasourceRequest.getTable())) {
                            TableField tableField = getTableFiled(resultSet, datasourceRequest);
                            list.add(tableField);
                        }
                    }
                }

            }
            resultSet.close();
        } catch (SQLException e) {
            DataEaseException.throwException(e);
        } catch (Exception e) {
            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase("ds_doris") || datasourceRequest.getDatasource().getType().equalsIgnoreCase("StarRocks")) {
                datasourceRequest.setQuery("select * from " + datasourceRequest.getTable());
                return fetchResultField(datasourceRequest);
            } else {
                DataEaseException.throwException(Translator.get("i18n_datasource_connect_error") + e.getMessage());
            }

        }
        return list;
    }

    private TableField getTableFiled(ResultSet resultSet, DatasourceRequest datasourceRequest) throws SQLException {
        TableField tableField = new TableField();
        String colName = resultSet.getString("COLUMN_NAME");
        tableField.setFieldName(colName);
        String remarks = resultSet.getString("REMARKS");
        if (remarks == null || remarks.equals("")) {
            remarks = colName;
        }
        tableField.setRemarks(remarks);
        String dbType = resultSet.getString("TYPE_NAME").toUpperCase();
        tableField.setFieldType(dbType);
        if (dbType.equalsIgnoreCase("LONG")) {
            tableField.setFieldSize(65533);
        }
        if (StringUtils.isNotEmpty(dbType) && dbType.toLowerCase().contains("date") && tableField.getFieldSize() < 50) {
            tableField.setFieldSize(50);
        }

        if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.ck.name())) {
            QueryProvider qp = ProviderFactory.getQueryProvider(datasourceRequest.getDatasource().getType());
            tableField.setFieldSize(qp.transFieldSize(dbType));
        } else {
            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.hive.name()) && tableField.getFieldType().equalsIgnoreCase("BOOLEAN")) {
                tableField.setFieldSize(1);
            } else {
                String size = resultSet.getString("COLUMN_SIZE");
                if (size == null) {
                    if (dbType.equals("JSON") && datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.mysql.name())) {
                        tableField.setFieldSize(65535);
                    } else {
                        tableField.setFieldSize(1);
                    }

                } else {
                    tableField.setFieldSize(Integer.valueOf(size));
                }
            }
        }
        if (StringUtils.isNotEmpty(tableField.getFieldType()) && tableField.getFieldType().equalsIgnoreCase("DECIMAL")) {
            tableField.setAccuracy(Integer.valueOf(resultSet.getString("DECIMAL_DIGITS")));
        }
        return tableField;
    }

    private String getDatabase(DatasourceRequest datasourceRequest) {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        switch (datasourceType) {
            case mysql:
            case engine_doris:
            case ds_doris:
            case mariadb:
            case TiDB:
            case StarRocks:
                MysqlConfiguration mysqlConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MysqlConfiguration.class);
                return mysqlConfiguration.getDataBase();
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                return sqlServerConfiguration.getDataBase();
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                return pgConfiguration.getDataBase();
            default:
                JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
                return jdbcConfiguration.getDataBase();
        }
    }

    private String getDsSchema(DatasourceRequest datasourceRequest) {
        JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
        return jdbcConfiguration.getSchema();
    }

    @Override
    public List<TableField> fetchResultField(DatasourceRequest datasourceRequest) throws Exception {
        JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
        int queryTimeout = jdbcConfiguration.getQueryTimeout() > 0 ? jdbcConfiguration.getQueryTimeout() : 0;
        try (Connection connection = getConnectionFromPool(datasourceRequest); Statement stat = connection.createStatement(); ResultSet rs = stat.executeQuery(datasourceRequest.getQuery())) {
            return fetchResultField(rs, datasourceRequest);
        } catch (SQLException e) {
            DataEaseException.throwException(e);
        } catch (Exception e) {
            e.printStackTrace();
            DataEaseException.throwException(Translator.get("i18n_datasource_connect_error") + e.getMessage());
        }
        return new ArrayList<>();
    }


    @Override
    public Map<String, List> fetchResultAndField(DatasourceRequest datasourceRequest) throws Exception {
        Map<String, List> result = new HashMap<>();
        List<String[]> dataList;
        List<TableField> fieldList;
        JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
        int queryTimeout = jdbcConfiguration.getQueryTimeout() > 0 ? jdbcConfiguration.getQueryTimeout() : 0;
        try (Connection connection = getConnectionFromPool(datasourceRequest); Statement stat = connection.createStatement(); ResultSet rs = stat.executeQuery(datasourceRequest.getQuery())) {
            fieldList = fetchResultField(rs, datasourceRequest);
            result.put("fieldList", fieldList);
            dataList = getDataResult(rs, datasourceRequest);
            result.put("dataList", dataList);
            return result;
        } catch (SQLException e) {
            DataEaseException.throwException(e);
        } catch (Exception e) {
            DataEaseException.throwException(e);
        }
        return new HashMap<>();
    }

    private List<String[]> getDataResult(ResultSet rs, DatasourceRequest datasourceRequest) throws Exception {
        String charset = null;
        String targetCharset = "UTF-8";
        if (datasourceRequest != null && datasourceRequest.getDatasource().getType().equalsIgnoreCase("oracle")) {
            JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
            //字符集
            if (StringUtils.isNotEmpty(jdbcConfiguration.getCharset()) && !jdbcConfiguration.getCharset().equalsIgnoreCase("Default")) {
                charset = jdbcConfiguration.getCharset();
            }
            //目标字符集
            if (StringUtils.isNotEmpty(jdbcConfiguration.getTargetCharset()) && !jdbcConfiguration.getTargetCharset().equalsIgnoreCase("Default")) {
                targetCharset = jdbcConfiguration.getTargetCharset();
            }
        }
        List<String[]> list = new LinkedList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        while (rs.next()) {
            String[] row = new String[columnCount];
            for (int j = 0; j < columnCount; j++) {
                //解析字段类型，设置mapping值
                int columnType = metaData.getColumnType(j + 1);
                String columTypeName = metaData.getColumnTypeName(j+1);
                switch (columnType) {
                    case Types.DATE:
                        if (rs.getDate(j + 1) != null) {
                            row[j] = rs.getDate(j + 1).toString();
                        }
                        break;
                    case Types.BOOLEAN:
                        row[j] = rs.getBoolean(j + 1) ? "1" : "0";
                        break;
                    default:
                        if (metaData.getColumnTypeName(j + 1).toLowerCase().equalsIgnoreCase("blob")) {
                            row[j] = rs.getBlob(j + 1) == null ? "" : rs.getBlob(j + 1).toString();
                        } else {
                            if (charset != null && StringUtils.isNotEmpty(rs.getString(j + 1))) {
                                //字符集 向 目标字符集转换
                                String originStr = new String(rs.getString(j + 1).getBytes(charset), targetCharset);
                                row[j] = new String(originStr.getBytes("UTF-8"), "UTF-8");
                            } else {
                                row[j] = rs.getString(j + 1);
                            }
                        }

                        break;
                }
            }
            list.add(row);
        }
        return list;
    }

    private List<TableField> fetchResultField(ResultSet resultSet, DatasourceRequest datasourceRequest) throws Exception {
        List<TableField> fieldList = new ArrayList<>();
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        for (int j = 0; j < columnCount; j++) {
            String columnName = metaData.getColumnName(j + 1);
            String columnLabel = StringUtils.isNotEmpty(metaData.getColumnLabel(j + 1)) ? metaData.getColumnLabel(j + 1) : columnName;
            String typeName = metaData.getColumnTypeName(j + 1);
            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.hive.name()) && columnLabel.contains(".")) {
                columnLabel = columnLabel.split("\\.")[1];
            }
            TableField field = new TableField();
            field.setFieldName(columnLabel);
            field.setRemarks(columnLabel);
            //字段类型
            field.setFieldType(typeName);

            if (datasourceRequest.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.ck.name())) {
                QueryProvider qp = ProviderFactory.getQueryProvider(datasourceRequest.getDatasource().getType());
                field.setFieldSize(qp.transFieldSize(typeName));
            } else {
                field.setFieldSize(metaData.getColumnDisplaySize(j + 1));
            }
            if (typeName.equalsIgnoreCase("LONG")) {
                field.setFieldSize(65533);
            } //oracle LONG
            if (StringUtils.isNotEmpty(typeName) && typeName.toLowerCase().contains("date") && field.getFieldSize() < 50) {
                field.setFieldSize(50);
            }
            fieldList.add(field);
        }
        return fieldList;
    }

    /**
     * 读取数据
     */
    @Override
    public List<String[]> getData(DatasourceRequest dsr) throws Exception {
        List<String[]> list = new LinkedList<>();
        //获取jdbc配置
        JdbcConfiguration jdbcConfiguration = new Gson().fromJson(dsr.getDatasource().getConfiguration(), JdbcConfiguration.class);
        int queryTimeout = jdbcConfiguration.getQueryTimeout() > 0 ? jdbcConfiguration.getQueryTimeout() : 0;
        //执行sql 查询

        try (
                //获取数据库连接（区分直接获取，或者从连接池获取）
                Connection connection = getConnectionFromPool(dsr);
                Statement stat = getStatement(connection, queryTimeout);
                //执行查询，获取结果
                ResultSet rs = stat.executeQuery(dsr.getQuery())) {
            //解析数据查询结果
            list = getDataResult(rs, dsr);

            //sqlServer db2数据库
            if (dsr.isPageable() && (dsr.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.sqlServer.name())
                    || dsr.getDatasource().getType().equalsIgnoreCase(DatasourceTypes.db2.name()))) {
                Integer realSize = dsr.getPage() * dsr.getPageSize() < list.size() ? dsr.getPage() * dsr.getPageSize() : list.size();
                list = list.subList((dsr.getPage() - 1) * dsr.getPageSize(), realSize);
            }

        } catch (SQLException e) {
            DataEaseException.throwException("SQL ERROR" + e.getMessage());
        } catch (Exception e) {
            DataEaseException.throwException("Data source connection exception: " + e.getMessage());
        }
        return list;
    }

    /**
     * 连接测试，检查数据库连接状态
     */
    @Override
    public String checkStatus(DatasourceRequest datasourceRequest) throws Exception {
        //获取测试SQL
        String queryStr = getTablesSql(datasourceRequest);
        JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
        //超时时间
        int queryTimeout = jdbcConfiguration.getQueryTimeout() > 0 ? jdbcConfiguration.getQueryTimeout() : 0;
        //从连接池里，获取数据库连接
        try (Connection con = getConnection(datasourceRequest);
             Statement statement = getStatement(con, queryTimeout);
             ResultSet resultSet = statement.executeQuery(queryStr)) {
        } catch (Exception e) {
            LogUtil.error("Datasource is invalid: " + datasourceRequest.getDatasource().getName(), e);
            io.dataease.plugins.common.exception.DataEaseException.throwException(e.getMessage());
        }
        return "Success";
    }

    /**
     * 直接使用驱动类来获取连接，不是通过连接池
     * */
    @Override
    public Connection getConnection(DatasourceRequest datasourceRequest) throws Exception {
        String username = null;
        String password = null;
        //驱动类名
        String defaultDriver = null;
        String jdbcurl = null;
        String customDriver = null;
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        Properties props = new Properties();
        DeDriver deDriver = null;
        switch (datasourceType) {
            case mysql:
            case mariadb:
            case engine_doris:
            case engine_mysql:
            case ds_doris:
            case TiDB:
            case StarRocks:
                MysqlConfiguration mysqlConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MysqlConfiguration.class);
                username = mysqlConfiguration.getUsername();
                password = mysqlConfiguration.getPassword();
                //驱动类名
                defaultDriver = "com.mysql.jdbc.Driver";
                jdbcurl = mysqlConfiguration.getJdbc();
                customDriver = mysqlConfiguration.getCustomDriver();
                break;
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                username = sqlServerConfiguration.getUsername();
                password = sqlServerConfiguration.getPassword();
                defaultDriver = sqlServerConfiguration.getDriver();
                customDriver = sqlServerConfiguration.getCustomDriver();
                jdbcurl = sqlServerConfiguration.getJdbc();
                break;
            case oracle:
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                username = oracleConfiguration.getUsername();
                password = oracleConfiguration.getPassword();
                defaultDriver = oracleConfiguration.getDriver();
                customDriver = oracleConfiguration.getCustomDriver();
                jdbcurl = oracleConfiguration.getJdbc();
                props.put("oracle.net.CONNECT_TIMEOUT", "5000");
                break;
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                username = pgConfiguration.getUsername();
                password = pgConfiguration.getPassword();
                defaultDriver = pgConfiguration.getDriver();
                customDriver = pgConfiguration.getCustomDriver();
                jdbcurl = pgConfiguration.getJdbc();
                break;
            case ck:
                CHConfiguration chConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), CHConfiguration.class);
                username = chConfiguration.getUsername();
                password = chConfiguration.getPassword();
                defaultDriver = chConfiguration.getDriver();
                customDriver = chConfiguration.getCustomDriver();
                jdbcurl = chConfiguration.getJdbc();
                break;
            case mongo:
                MongodbConfiguration mongodbConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MongodbConfiguration.class);
                username = mongodbConfiguration.getUsername();
                password = mongodbConfiguration.getPassword();
                defaultDriver = mongodbConfiguration.getDriver();
                customDriver = mongodbConfiguration.getCustomDriver();
                jdbcurl = mongodbConfiguration.getJdbc(datasourceRequest.getDatasource().getId());
                break;
            case redshift:
                RedshiftConfiguration redshiftConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), RedshiftConfiguration.class);
                username = redshiftConfiguration.getUsername();
                password = redshiftConfiguration.getPassword();
                defaultDriver = redshiftConfiguration.getDriver();
                customDriver = redshiftConfiguration.getCustomDriver();
                jdbcurl = redshiftConfiguration.getJdbc();
                break;
            case hive:
                HiveConfiguration hiveConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), HiveConfiguration.class);
                defaultDriver = hiveConfiguration.getDriver();
                customDriver = hiveConfiguration.getCustomDriver();
                jdbcurl = hiveConfiguration.getJdbc();

                if (StringUtils.isNotEmpty(hiveConfiguration.getAuthMethod()) && hiveConfiguration.getAuthMethod().equalsIgnoreCase("kerberos")) {
                    System.setProperty("java.security.krb5.conf", "/opt/dataease/conf/krb5.conf");
                    ExtendedJdbcClassLoader classLoader;
                    if (isDefaultClassLoader(customDriver)) {
                        classLoader = extendedJdbcClassLoader;
                    } else {
                        deDriver = deDriverMapper.selectByPrimaryKey(customDriver);
                        classLoader = getCustomJdbcClassLoader(deDriver);
                    }
                    Class<?> ConfigurationClass = classLoader.loadClass("org.apache.hadoop.conf.Configuration");
                    Method set = ConfigurationClass.getMethod("set", String.class, String.class);
                    Object obj = ConfigurationClass.newInstance();
                    set.invoke(obj, "hadoop.security.authentication", "Kerberos");

                    Class<?> UserGroupInformationClass = classLoader.loadClass("org.apache.hadoop.security.UserGroupInformation");
                    Method setConfiguration = UserGroupInformationClass.getMethod("setConfiguration", ConfigurationClass);
                    Method loginUserFromKeytab = UserGroupInformationClass.getMethod("loginUserFromKeytab", String.class, String.class);
                    setConfiguration.invoke(null, obj);
                    loginUserFromKeytab.invoke(null, hiveConfiguration.getUsername(), "/opt/dataease/conf/" + hiveConfiguration.getPassword());
                } else {
                    username = hiveConfiguration.getUsername();
                    password = hiveConfiguration.getPassword();
                }
                break;
            case impala:
                ImpalaConfiguration impalaConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), ImpalaConfiguration.class);
                username = impalaConfiguration.getUsername();
                password = impalaConfiguration.getPassword();
                defaultDriver = impalaConfiguration.getDriver();
                customDriver = impalaConfiguration.getCustomDriver();
                jdbcurl = impalaConfiguration.getJdbc();
                break;
            case db2:
                Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
                username = db2Configuration.getUsername();
                password = db2Configuration.getPassword();
                defaultDriver = db2Configuration.getDriver();
                customDriver = db2Configuration.getCustomDriver();
                jdbcurl = db2Configuration.getJdbc();
                break;
            default:
                break;
        }

        if (StringUtils.isNotBlank(username)) {
            props.setProperty("user", username);
            if (StringUtils.isNotBlank(password)) {
                props.setProperty("password", password);
            }
        }

        Connection conn;
        String driverClassName;
        ExtendedJdbcClassLoader jdbcClassLoader;
        //默认的驱动
        if (isDefaultClassLoader(customDriver)) {
            driverClassName = defaultDriver;
            jdbcClassLoader = extendedJdbcClassLoader;
        }
        //自定义的驱动
        else {
            if (deDriver == null) {
                deDriver = deDriverMapper.selectByPrimaryKey(customDriver);
            }
            driverClassName = deDriver.getDriverClass();
            //自定义ClassLoader ， 去加载指定位置的jdbc驱动类
            jdbcClassLoader = getCustomJdbcClassLoader(deDriver);
        }

        Driver driverClass = (Driver) jdbcClassLoader.loadClass(driverClassName).newInstance();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(jdbcClassLoader);
            System.out.println("jdbcurl "+ jdbcurl);
            conn = driverClass.connect(jdbcurl, props);

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        } finally {
            Thread.currentThread().setContextClassLoader(classLoader);
        }
        return conn;
    }


    @Override
    public JdbcConfiguration setCredential(DatasourceRequest datasourceRequest, DruidDataSource dataSource) throws Exception {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        JdbcConfiguration jdbcConfiguration = new JdbcConfiguration();
        switch (datasourceType) {
            case mysql:
            case mariadb:
            case engine_mysql:
            case engine_doris:
            case ds_doris:
            case TiDB:
            case StarRocks:
                MysqlConfiguration mysqlConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MysqlConfiguration.class);
                dataSource.setUrl(mysqlConfiguration.getJdbc());
                dataSource.setDriverClassName("com.mysql.jdbc.Driver");
                dataSource.setValidationQuery("select 1");
                jdbcConfiguration = mysqlConfiguration;
                break;
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                dataSource.setDriverClassName(sqlServerConfiguration.getDriver());
                dataSource.setUrl(sqlServerConfiguration.getJdbc());
                dataSource.setValidationQuery("select 1");
                jdbcConfiguration = sqlServerConfiguration;
                break;
            case oracle:
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                dataSource.setDriverClassName(oracleConfiguration.getDriver());
                dataSource.setUrl(oracleConfiguration.getJdbc());
                dataSource.setValidationQuery("select 1 from dual");
                jdbcConfiguration = oracleConfiguration;
                break;
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                dataSource.setDriverClassName(pgConfiguration.getDriver());
                dataSource.setUrl(pgConfiguration.getJdbc());
                jdbcConfiguration = pgConfiguration;
                break;
            case ck:
                CHConfiguration chConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), CHConfiguration.class);
                dataSource.setDriverClassName(chConfiguration.getDriver());
                dataSource.setUrl(chConfiguration.getJdbc());
                jdbcConfiguration = chConfiguration;
                break;
            case mongo:
                MongodbConfiguration mongodbConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), MongodbConfiguration.class);
                dataSource.setDriverClassName(mongodbConfiguration.getDriver());
                dataSource.setUrl(mongodbConfiguration.getJdbc(datasourceRequest.getDatasource().getId()));
                jdbcConfiguration = mongodbConfiguration;
                break;
            case redshift:
                RedshiftConfiguration redshiftConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), RedshiftConfiguration.class);
                dataSource.setPassword(redshiftConfiguration.getPassword());
                dataSource.setDriverClassName(redshiftConfiguration.getDriver());
                dataSource.setUrl(redshiftConfiguration.getJdbc());
                jdbcConfiguration = redshiftConfiguration;
                break;
            case hive:
                HiveConfiguration hiveConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), HiveConfiguration.class);
                dataSource.setPassword(hiveConfiguration.getPassword());
                dataSource.setDriverClassName(hiveConfiguration.getDriver());
                dataSource.setUrl(hiveConfiguration.getJdbc());
                jdbcConfiguration = hiveConfiguration;
                break;
            case impala:
                ImpalaConfiguration impalaConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), ImpalaConfiguration.class);
                dataSource.setPassword(impalaConfiguration.getPassword());
                dataSource.setDriverClassName(impalaConfiguration.getDriver());
                dataSource.setUrl(impalaConfiguration.getJdbc());
                jdbcConfiguration = impalaConfiguration;
                break;
            case db2:
                Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
                dataSource.setPassword(db2Configuration.getPassword());
                dataSource.setDriverClassName(db2Configuration.getDriver());
                dataSource.setUrl(db2Configuration.getJdbc());
                jdbcConfiguration = db2Configuration;
            default:
                break;
        }

        dataSource.setUsername(jdbcConfiguration.getUsername());

        ExtendedJdbcClassLoader classLoader;
        if (isDefaultClassLoader(jdbcConfiguration.getCustomDriver())) {
            classLoader = extendedJdbcClassLoader;
        } else {
            DeDriver deDriver = deDriverMapper.selectByPrimaryKey(jdbcConfiguration.getCustomDriver());
            classLoader = getCustomJdbcClassLoader(deDriver);
        }
        dataSource.setDriverClassLoader(classLoader);
        dataSource.setPassword(jdbcConfiguration.getPassword());

        return jdbcConfiguration;
    }

    /**
     * 检查是否存在指定数据库
     */
    @Override
    public String getTablesSql(DatasourceRequest datasourceRequest) throws Exception {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        switch (datasourceType) {
            case mysql:
            case engine_mysql:
            case mariadb:
            case TiDB:
                JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), JdbcConfiguration.class);
                return String.format("SELECT TABLE_NAME,TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '%s' ;",
                        jdbcConfiguration.getDataBase());
            case engine_doris:
            case ds_doris:
            case StarRocks:
            case hive:
            case impala:
                return "show tables";
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                if (StringUtils.isEmpty(sqlServerConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT TABLE_NAME FROM \"DATABASE\".INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA = 'DS_SCHEMA' ;"
                        .replace("DATABASE", sqlServerConfiguration.getDataBase())
                        .replace("DS_SCHEMA", sqlServerConfiguration.getSchema());
            case oracle:
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                if (StringUtils.isEmpty(oracleConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "select table_name, owner, comments from all_tab_comments where owner='OWNER' AND table_type = 'TABLE' AND table_name in (select table_name from all_tables where owner='OWNER')".replaceAll("OWNER", oracleConfiguration.getSchema());
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                if (StringUtils.isEmpty(pgConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT tablename FROM  pg_tables WHERE  schemaname='SCHEMA' ;".replace("SCHEMA", pgConfiguration.getSchema());
            case ck:
                CHConfiguration chConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), CHConfiguration.class);
                return "SELECT name FROM system.tables where database='DATABASE';".replace("DATABASE", chConfiguration.getDataBase());
            case redshift:
                RedshiftConfiguration redshiftConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), RedshiftConfiguration.class);
                if (StringUtils.isEmpty(redshiftConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT tablename FROM  pg_tables WHERE  schemaname='SCHEMA' ;".replace("SCHEMA", redshiftConfiguration.getSchema());
            case db2:
                Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
                if (StringUtils.isEmpty(db2Configuration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "select TABNAME from syscat.tables  WHERE TABSCHEMA ='DE_SCHEMA' AND \"TYPE\" = 'T'".replace("DE_SCHEMA", db2Configuration.getSchema());
            default:
                return "show tables;";
        }
    }

    @Override
    public String getViewSql(DatasourceRequest datasourceRequest) throws Exception {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        switch (datasourceType) {
            case mysql:
            case mariadb:
            case engine_doris:
            case engine_mysql:
            case ds_doris:
            case ck:
            case TiDB:
            case StarRocks:
                return null;
            case sqlServer:
                SqlServerConfiguration sqlServerConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), SqlServerConfiguration.class);
                if (StringUtils.isEmpty(sqlServerConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT TABLE_NAME FROM \"DATABASE\".INFORMATION_SCHEMA.VIEWS WHERE  TABLE_SCHEMA = 'DS_SCHEMA' ;"
                        .replace("DATABASE", sqlServerConfiguration.getDataBase())
                        .replace("DS_SCHEMA", sqlServerConfiguration.getSchema());
            case oracle:
                OracleConfiguration oracleConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), OracleConfiguration.class);
                if (StringUtils.isEmpty(oracleConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "select table_name, owner, comments from all_tab_comments where owner='" + oracleConfiguration.getSchema() + "' AND table_type = 'VIEW'";
            case pg:
                PgConfiguration pgConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), PgConfiguration.class);
                if (StringUtils.isEmpty(pgConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT viewname FROM  pg_views WHERE schemaname='SCHEMA' ;".replace("SCHEMA", pgConfiguration.getSchema());
            case redshift:
                RedshiftConfiguration redshiftConfiguration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), RedshiftConfiguration.class);
                if (StringUtils.isEmpty(redshiftConfiguration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "SELECT viewname FROM  pg_views WHERE schemaname='SCHEMA' ;".replace("SCHEMA", redshiftConfiguration.getSchema());

            case db2:
                Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
                if (StringUtils.isEmpty(db2Configuration.getSchema())) {
                    throw new Exception(Translator.get("i18n_schema_is_empty"));
                }
                return "select TABNAME from syscat.tables  WHERE TABSCHEMA ='DE_SCHEMA' AND \"TYPE\" = 'V'".replace("DE_SCHEMA", db2Configuration.getSchema());

            default:
                return null;
        }
    }

    @Override
    public String getSchemaSql(DatasourceRequest datasourceRequest) {
        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasourceRequest.getDatasource().getType());
        Db2Configuration db2Configuration = new Gson().fromJson(datasourceRequest.getDatasource().getConfiguration(), Db2Configuration.class);
        switch (datasourceType) {
            case oracle:
                return "select * from all_users";
            case sqlServer:
                return "select name from sys.schemas;";
            case db2:
                return "select SCHEMANAME from syscat.SCHEMATA   WHERE \"DEFINER\" ='USER'".replace("USER", db2Configuration.getUsername().toUpperCase());
            case pg:
                return "SELECT nspname FROM pg_namespace;";
            case redshift:
                return "SELECT nspname FROM pg_namespace;";
            default:
                return "show tables;";
        }
    }

    @Override
    public void checkConfiguration(Datasource datasource) throws Exception {
        if (StringUtils.isEmpty(datasource.getConfiguration())) {
            throw new Exception("Datasource configuration is empty");
        }
        try {
            JdbcConfiguration jdbcConfiguration = new Gson().fromJson(datasource.getConfiguration(), JdbcConfiguration.class);
            if (jdbcConfiguration.getQueryTimeout() < 0) {
                throw new Exception("Querytimeout cannot be less than zero.");
            }
        } catch (Exception e) {
            throw new Exception("Invalid configuration: " + e.getMessage());
        }

        DatasourceTypes datasourceType = DatasourceTypes.valueOf(datasource.getType());
        switch (datasourceType) {
            case mysql:
            case mariadb:
            case engine_doris:
            case engine_mysql:
            case ds_doris:
            case TiDB:
            case StarRocks:
                MysqlConfiguration mysqlConfiguration = new Gson().fromJson(datasource.getConfiguration(), MysqlConfiguration.class);
                mysqlConfiguration.getJdbc();
            default:
                break;
        }
    }


}
