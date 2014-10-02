package com.uptimesoftware.uptime.plugin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.fortsoft.pf4j.PluginWrapper;
import com.uptimesoftware.uptime.plugin.api.Extension;
import com.uptimesoftware.uptime.plugin.api.Plugin;
import com.uptimesoftware.uptime.plugin.api.PluginMonitor;
import com.uptimesoftware.uptime.plugin.monitor.MonitorState;
import com.uptimesoftware.uptime.plugin.monitor.Parameters;

/**
 * MS SQL Basic (with retained data) Monitor.
 * 
 * The plugin provides same functionality as the built-in MS SQL Basic monitor but stores the query results in a numeric
 * result for thresholding or comparison.
 * 
 * @author uptime software
 */
public class MonitorSQLServerQuery extends Plugin {

	/**
	 * Constructor - a plugin wrapper.
	 * 
	 * @param wrapper
	 */
	public MonitorSQLServerQuery(PluginWrapper wrapper) {
		super(wrapper);
	}

	/**
	 * A nested static class which has to extend PluginMonitor.
	 * 
	 * Functions that require implementation :
	 * 1) The monitor function will implement the main functionality and should set the monitor's state and result
	 * message prior to completion.
	 * 2) The setParameters function will accept a Parameters object containing the values filled into the monitor's
	 * configuration page in Uptime.
	 */
	@Extension
	public static class UptimeMonitorSQLServerQuery extends PluginMonitor {
		// Logger object.
		private static final Logger LOGGER = LoggerFactory.getLogger(UptimeMonitorSQLServerQuery.class);

		// Constants
		private final static String JTDS_CLASS = "net.sourceforge.jtds.jdbc.Driver";
		private final static String WINDOWS_AUTH = "Windows Authentication";
		private final static int OUTPUT_TYPE_LONG = 0;
		private final static int OUTPUT_TYPE_DOUBLE = 1;
		private final static int OUTPUT_TYPE_STRING = 2;
		private final static String ERROR_WIN_AUTH_WITH_NO_DOMAIN = "winAuthButNoDomain";
		private final static String ERROR_DOMAIN_BUT_NO_WIN_AUTH = "domainButNoWinAuth";

		// Store numeric value here if the output String contains numeric value.
		private long longValue = 0;
		private double doubleValue = 0;
		// Number of row in a result set.
		private int rowCounter = 0;

		// See definition in .xml file for plugin. Each plugin has different number of input/output parameters.
		// [Input]
		String authenticationMethod = "";
		String hostname = "";
		int port = 0;
		String domain = "";
		String username = "";
		String password = "";
		String instance = "";
		String database = "";
		String sqlQuery = "";

		/**
		 * The setParameters function will accept a Parameters object containing the values filled into the monitor's
		 * configuration page in Up.time.
		 * 
		 * @param params
		 *            Parameters object which contains inputs.
		 */
		@Override
		public void setParameters(Parameters params) {
			LOGGER.debug("Step 1 : Setting parameters.");
			// [Input]
			authenticationMethod = params.getString("authenticationMethod");
			hostname = params.getString("hostname");
			port = params.getInt("port");
			domain = params.getString("domain");
			username = params.getString("username");
			password = params.getString("password");
			instance = params.getString("instance");
			database = params.getString("database");
			sqlQuery = params.getString("sqlQuery");

			// Get rid of semicolons at the end of sqlQuery String if there is. JDBC won't recognize semicolon at the
			// end of query String.
			deleteSemicolonAtTheEnd(sqlQuery);
		}

		/**
		 * The monitor function will implement the main functionality and should set the monitor's state and result
		 * message prior to completion.
		 */
		@Override
		public void monitor() {
			LOGGER.debug("Step 2 : Form a URL for database connection.");
			String url = formURL(hostname, port, database, instance, domain);

			LOGGER.debug("Error handling : If Windows Authentication is selected but domain is not given, change monitor state to CRIT.");
			if (url.equals(ERROR_WIN_AUTH_WITH_NO_DOMAIN)) {
				setStateAndMessage(MonitorState.CRIT,
						"Windows Authentication is selected but domain input is not given.");
				return;
			}

			// When a user selects Windows Authentication option on the built-in SQL Server (Basic Checks) monitor,
			// 'Domain' input field appears. Otherwise, 'Domain' input field is hidden. However this functionality is
			// not
			// included in the Java plugin SDK yet so we have to check that domain input field remains empty when Win
			// Auth is
			// NOT selected.
			LOGGER.debug("Error handling : If domain input is given but Windows Authentication is not selected, change monitor state to CRIT.");
			if (url.equals(ERROR_DOMAIN_BUT_NO_WIN_AUTH)) {
				setStateAndMessage(MonitorState.CRIT,
						"Domain input is given but Windows Authentication is not selected.");
				return;
			}

			LOGGER.debug("Step 3 : Connect to the database with the given parameters and authentication method.");
			Connection connection = getRemoteConnection(url, username, password);

			LOGGER.debug("Error handling : If connecting fails, change monitor state to CRIT.");
			if (connection == null) {
				setStateAndMessage(MonitorState.CRIT, "Could not connect to database, check monitor settings.");
				// connection is null. Plugin should stop here.
				return;
			}

			LOGGER.debug("Step 4 : Create a PreparedStatement for sending parameterized SQL statements to the database.");
			PreparedStatement preparedStatement = prepareStatement(connection, sqlQuery);

			LOGGER.debug("Error handling : If creating statement fails, set monitor state CRIT and set an error message.");
			if (preparedStatement == null) {
				setStateAndMessage(MonitorState.CRIT, "Could not get prepared statement, check connection object.");
				// preparedStatement is null. Plugin should stop here.
				return;
			}

			LOGGER.debug("Step 5 : Preparing statement was successful. Execute the prepared statement and get result set.");
			ResultSet resultSet = getResultSet(preparedStatement);

			LOGGER.debug("Error handling : If getting result set fails, set monitor state CRIT and set an error message.");
			// Although executeQuery() never returns null according to JDBC API, just making sure.
			if (resultSet == null) {
				setStateAndMessage(MonitorState.CRIT, "Could not get result set, check preparedStatement object.");
				// resultSet is null. Plugin should stop here.
				return;
			}
			LOGGER.debug("Step 6 : Getting a result set was successful. Extract result from the result set and set output.");
			String output = extractFromResultSet(resultSet).trim();
			switch (isLongDoubleOrText(output)) {
			case OUTPUT_TYPE_LONG:
				addVariable("numberOutput", longValue);
				break;
			case OUTPUT_TYPE_DOUBLE:
				addVariable("numberOutput", doubleValue);
				break;
			case OUTPUT_TYPE_STRING:
				addVariable("textOutput", output);
				break;
			}
			// set number of row in result set to output.
			addVariable("rowCounter", rowCounter);

			LOGGER.debug("Step 7 : close all (connection, preparedStatement, resultSet).");
			closeAll(connection, preparedStatement, resultSet);

			LOGGER.debug("Step 8 : Set monitor state to OK and set SUCCESSFUL message.");
			setStateAndMessage(MonitorState.OK, "Monitor successfully ran.");
		}

		/**
		 * Private helper method to delete semicolon at the end of SQL query String.
		 * 
		 * @param sqlQuery
		 *            SQL Query String
		 */
		private void deleteSemicolonAtTheEnd(String sqlQuery) {
			int sqlQueryLength = sqlQuery.length();
			// If the char at the last position of sqlQuery String is semicolon and the length is bigger than 1, then
			// get rid of semicolon at the end of sqlQuery String.
			if ((sqlQuery.charAt(sqlQueryLength - 1) == ';') && (sqlQueryLength >= 2)) {
				LOGGER.debug("The sqlQuery String contains semicolon(s) at the end. Deleting selemicolon(s).");
				this.sqlQuery = sqlQuery.substring(0, sqlQuery.length() - 1).trim();
				// Recursion : run it until there is no semicolon(s) at the end of sqlQuery String.
				deleteSemicolonAtTheEnd(this.sqlQuery);
			}
		}

		/**
		 * Private helper function to form MSSQL URL with instance.
		 * 
		 * @param hostname
		 *            Name of host
		 * @param port
		 *            port number
		 * @param databaseName
		 *            Port number
		 * @param instance
		 *            DB Instance
		 * @param domain
		 *            Domain
		 * @return URL String.
		 */
		private String formURL(String hostname, int port, String databaseName, String instance, String domain) {
			String url = "jdbc:jtds:sqlserver://" + hostname + ":" + port;
			// If databaseName is not null/empty, add databaaseName to URL.
			url += (databaseName != null && !databaseName.equals("")) ? "/" + databaseName : "";
			// If instance is not null/empty, add instance property to URL String.
			url += (instance != null && !instance.equals("")) ? ";instance=" + instance : "";
			if (authenticationMethod.equals(WINDOWS_AUTH) && domain != null) {
				// If domain is not null/empty, add domain property to URL String.
				url += ";domain=" + domain;
			} else if (!authenticationMethod.equals(WINDOWS_AUTH) && domain != null) {
				url = ERROR_DOMAIN_BUT_NO_WIN_AUTH;
			} else if (authenticationMethod.equals(WINDOWS_AUTH) && domain == null) {
				url = ERROR_WIN_AUTH_WITH_NO_DOMAIN;
			}
			return url;
		}

		/**
		 * Private helper function to get database connection.
		 * 
		 * @param connectionURL
		 *            connection URL String
		 * @param username
		 *            Name of user.
		 * @param password
		 *            Password
		 * @return Database connection object.
		 */
		private Connection getRemoteConnection(String connectionURL, String username, String password) {
			Connection connection = null;
			try {
				// Specify the jTDS driver.
				Class.forName(JTDS_CLASS);
				connection = DriverManager.getConnection(connectionURL, username, password);
				LOGGER.debug("Make sure connection is still open before moving on.");
				if (connection.isClosed()) {
					setStateAndMessage(MonitorState.CRIT, "Connection is closed.");
				}
			} catch (SQLException e) {
				LOGGER.error("Error while getting remote connection.", e);
			} catch (ClassNotFoundException e) {
				LOGGER.error("Error while returning the specific Class object", e);
			}
			return connection;
		}

		/**
		 * Private helper function to get PreparedStatement with given Connection and SQL script/query.
		 * 
		 * @param connection
		 *            Connection object of database
		 * @param script
		 *            SQL script/query to run.
		 * @return PreparedStatement object containing the pre-compiled SQL statement.
		 */
		private PreparedStatement prepareStatement(Connection connection, String sqlScript) {
			PreparedStatement preparedStatement = null;
			try {
				preparedStatement = connection.prepareStatement(sqlScript);
			} catch (SQLException e) {
				LOGGER.error("Error while creating PreparedStatement failed : ", e);
			}
			return preparedStatement;
		}

		/**
		 * Private helper function to execute prepared statement and get result set.
		 * 
		 * @param preparedStatement
		 *            PreparedStatement object containing the pre-compiled SQL statement.
		 * @return Result set after executing prepared statement. (executeQuery() never returns null according to JDBC
		 *         API)
		 */
		private ResultSet getResultSet(PreparedStatement preparedStatement) {
			ResultSet resultSet = null;
			try {
				resultSet = preparedStatement.executeQuery();
			} catch (SQLException e) {
				LOGGER.error("Error while executing prepared statement : ", e);
			}
			return resultSet;
		}

		/**
		 * Private helper function to extract String result from the given ResultSet.
		 * 
		 * @param rs
		 *            ResultSet object
		 * @return Extracted String result.
		 */
		private String extractFromResultSet(ResultSet rs) {
			String extractedStringResult = "";
			try {
				// An object that can be used to get information about the types and properties of the columns in a
				// ResultSet object.
				ResultSetMetaData meta = rs.getMetaData();
				int columnCount = meta.getColumnCount();
				while (rs.next()) {
					rowCounter++;
					// Build one raw String result with the private helper function.
					extractedStringResult = getRowAsString(rs, columnCount);
				}
				// Is it necessary to check empty result and warn a user?
				if (extractedStringResult.isEmpty()) {
					LOGGER.warn("The String result is empty.");
				}
			} catch (SQLException e) {
				LOGGER.error("Error while extracting results from the given ResultSet : ", e);
			}
			return extractedStringResult;
		}

		/**
		 * Private helper function to build one String result instead of making a Map<Key, Value>.
		 * 
		 * @param rs
		 *            ResultSet object
		 * @param columnCount
		 *            Number of column count in the ResultSet object.
		 * @return One String object which contains all rows of the ResultSet. (each rows are divided by a space)
		 */
		private String getRowAsString(ResultSet rs, int columnCount) {
			StringBuilder rowString = new StringBuilder();
			// 1-based index
			for (int i = 1; i <= columnCount; i++) {
				try {
					rowString.append(rs.getString(i));
				} catch (SQLException e) {
					LOGGER.error(
							"Error while building one raw String result. Problem occurred when getting String from column number ["
									+ i + "]", e);
				}
				// Insert a space between each columns.
				rowString.append(" ");
			}
			// Return one raw String result.
			return rowString.toString();
		}

		/**
		 * Private helper function to check if the String result contains Long/Double numeric value or not.
		 * 
		 * @param stringResult
		 *            String result
		 * @return 0 if Long, 1 if Double, 2 if non-numeric String
		 */
		private int isLongDoubleOrText(String stringResult) {
			// 0 if Long, 1 if Double, 2 if non-numeric String
			int which = OUTPUT_TYPE_STRING;
			try {
				if (stringResult.contains(".")) {
					doubleValue = Double.parseDouble(stringResult);
					which = OUTPUT_TYPE_DOUBLE;
				} else {
					longValue = Long.parseLong(stringResult);
					which = OUTPUT_TYPE_LONG;
				}
			} catch (NumberFormatException e) {
				// Do Nothing.
			}
			return which;
		}

		/**
		 * Private helper function to close all if they're open.
		 * 
		 * @param connection
		 *            Connection object
		 * @param preparedStatement
		 *            PreparedStatement object
		 * @param resultSet
		 *            ResultSet object
		 */
		private void closeAll(Connection connection, PreparedStatement preparedStatement, ResultSet resultSet) {
			// There are better ways to do this. Fix it later.
			try {
				if (!resultSet.isClosed()) {
					resultSet.close();
				}
				if (!preparedStatement.isClosed()) {
					preparedStatement.close();
				}
				if (!connection.isClosed()) {
					connection.close();
				}
			} catch (SQLException e) {
				LOGGER.error("Error while closing all.", e);
			}
		}
	}
}