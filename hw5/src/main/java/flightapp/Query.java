package flightapp;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Runs queries against a back-end database
 */
public class Query {
  // DB Connection
  private Connection conn;

  // Password hashing parameter constants
  private static final int HASH_STRENGTH = 65536;
  private static final int KEY_LENGTH = 128;

  // Canned queries
  private static final String CHECK_FLIGHT_CAPACITY = "SELECT capacity FROM Flights WHERE fid = ?";
  private PreparedStatement checkFlightCapacityStatement;

  // For check dangling
  private static final String TRANCOUNT_SQL = "SELECT @@TRANCOUNT AS tran_count";
  private PreparedStatement tranCountStatement;

  // TODO: YOUR CODE HERE
  private static final String CLEAR_TABLE = "DELETE FROM ?";
  private PreparedStatement clearTableStatement;

  private static final String CREATE_USER = "INSERT INTO USERS (username, password, salt, balance) VALUES (?, ?, ?, ?)";
  private PreparedStatement createUserStatement;

  private static final String FIND_USER = "SELECT * FROM USERS WHERE username = ?";
  private PreparedStatement findUserStatement;

  private static final String GET_DIRECT_FLIGHTS = "SELECT TOP (?) fid, day_of_month, carrier_id, flight_num, origin_city, dest_city, actual_time, capacity, price "
      + "FROM FLIGHTS WHERE origin_city = ? AND dest_city = ? AND canceled = 0 AND day_of_month = ? ORDER BY actual_time ASC, fid ASC";
  private PreparedStatement getDirectFlightsStatement;

  private static final String GET_INDIRECT_FLIGHTS = "SELECT TOP (?) F1.fid AS F1_fid, F1.day_of_month AS F1_day_of_month, F1.carrier_id AS F1_carrier_id, F1.flight_num AS F1_flight_num, "
      + "F1.origin_city AS F1_origin_city, F1.dest_city AS F1_dest_city, F1.actual_time AS F1_actual_time, F1.capacity AS F1_capacity, F1.price AS F1_price, "
      + "F2.fid AS F2_fid, F2.day_of_month AS F2_day_of_month, F2.carrier_id AS F2_carrier_id, F2.flight_num AS F2_flight_num, F2.origin_city AS F2_origin_city, F2.dest_city AS F2_dest_city, "
      + "F2.actual_time AS F2_actual_time, F2.capacity As F2_capacity, F2.price AS F2_price "
      + "FROM FLIGHTS AS F1, FLIGHTS AS F2 "
      + "WHERE F1.origin_city = ? AND F2.dest_city = ? AND F1.dest_city = F2.origin_city AND F2.origin_city = F1.dest_city AND F1.canceled = 0"
      + "AND F1.day_of_month = ? AND F2.day_of_month = ? AND F2.canceled = 0 ORDER BY F1.actual_time + F2.actual_time, F1.fid, F2.fid";
  private PreparedStatement getIndirectFlightsStatement;

  private User user;

  public Query() throws SQLException, IOException {
    this(null, null, null, null);
  }

  protected Query(String serverURL, String dbName, String adminName, String password) throws SQLException, IOException {
    conn = serverURL == null ? openConnectionFromDbConn()
        : openConnectionFromCredential(serverURL, dbName, adminName, password);

    prepareStatements();
  }

  /**
   * Return a connecion by using dbconn.properties file
   *
   * @throws SQLException
   * @throws IOException
   */
  public static Connection openConnectionFromDbConn() throws SQLException, IOException {
    // Connect to the database with the provided connection configuration
    Properties configProps = new Properties();
    configProps.load(new FileInputStream("dbconn.properties"));
    String serverURL = configProps.getProperty("flightapp.server_url");
    String dbName = configProps.getProperty("flightapp.database_name");
    String adminName = configProps.getProperty("flightapp.username");
    String password = configProps.getProperty("flightapp.password");
    return openConnectionFromCredential(serverURL, dbName, adminName, password);
  }

  /**
   * Return a connecion by using the provided parameter.
   *
   * @param serverURL example: example.database.widows.net
   * @param dbName    database name
   * @param adminName username to login server
   * @param password  password to login server
   *
   * @throws SQLException
   */
  protected static Connection openConnectionFromCredential(String serverURL, String dbName, String adminName,
      String password) throws SQLException {
    String connectionUrl = String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
        dbName, adminName, password);
    Connection conn = DriverManager.getConnection(connectionUrl);

    // By default, automatically commit after each statement
    conn.setAutoCommit(true);

    // By default, set the transaction isolation level to serializable
    conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

    return conn;
  }

  /**
   * Get underlying connection
   */
  public Connection getConnection() {
    return conn;
  }

  /**
   * Closes the application-to-database connection
   */
  public void closeConnection() throws SQLException {
    conn.close();
  }

  /**
   * Clear the data in any custom tables created.
   * 
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() {
    try {
      clearTableStatement.setString(1, "USERS");
      clearTableStatement.execute();

      clearTableStatement.setString(1, "RESERVATIONS");
      clearTableStatement.execute();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /*
   * prepare all the SQL statements in this method.
   */
  private void prepareStatements() throws SQLException {
    checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
    tranCountStatement = conn.prepareStatement(TRANCOUNT_SQL);
    // TODO: YOUR CODE HERE
    clearTableStatement = conn.prepareStatement(CLEAR_TABLE);
    createUserStatement = conn.prepareStatement(CREATE_USER);
    findUserStatement = conn.prepareStatement(FIND_USER);
    getDirectFlightsStatement = conn.prepareStatement(GET_DIRECT_FLIGHTS);
    getIndirectFlightsStatement = conn.prepareStatement(GET_INDIRECT_FLIGHTS);
  }

  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username user's username
   * @param password user's password
   *
   * @return If someone has already logged in, then return "User already logged
   *         in\n" For all other errors, return "Login failed\n". Otherwise,
   *         return "Logged in as [username]\n".
   */
  public String transaction_login(String username, String password) {
    try {
      // TODO: YOUR CODE HERE
      if (user != null) {
        return "User already logged in\n";
      }

      findUserStatement.setString(1, username.toLowerCase());
      ResultSet rs = findUserStatement.executeQuery();

      if (!rs.next()) {
        return "Login failed\n";
      }

      byte[] salt = rs.getBytes(3);

      // Specify the hash parameters
      KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);

      // Generate the hash
      SecretKeyFactory factory = null;
      byte[] hash = null;
      try {
        factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        hash = factory.generateSecret(spec).getEncoded();
      } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
        throw new IllegalStateException();
      }

      if (!Arrays.equals(rs.getBytes(2), hash)) {
        return "Login failed\n";
      }

      user = new User(rs.getString(1), rs.getInt(4));

      return "Logged in as " + username + "\n";
    } catch (Exception e) {
      return "Login failed\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implement the create user function.
   *
   * @param username   new user's username. User names are unique the system.
   * @param password   new user's password.
   * @param initAmount initial amount to deposit into the user's account, should
   *                   be >= 0 (failure otherwise).
   *
   * @return either "Created user {@code username}\n" or "Failed to create user\n"
   *         if failed.
   */
  public String transaction_createCustomer(String username, String password, int initAmount) {
    try {
      // TODO: YOUR CODE HERE
      if (initAmount < 0) {
        return "Failed to create user\n";
      }

      // Generate a random cryptographic salt
      SecureRandom random = new SecureRandom();
      byte[] salt = new byte[16];
      random.nextBytes(salt);

      // Specify the hash parameters
      KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);

      // Generate the hash
      SecretKeyFactory factory = null;
      byte[] hash = null;
      try {
        factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        hash = factory.generateSecret(spec).getEncoded();
      } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
        throw new IllegalStateException();
      }

      createUserStatement.setString(1, username.toLowerCase());
      createUserStatement.setBytes(2, hash);
      createUserStatement.setBytes(3, salt);
      createUserStatement.setInt(4, initAmount);
      createUserStatement.execute();

      return "Created user " + username + "\n";
    } catch (Exception e) {
      return "Failed to create user\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implement the search function.
   *
   * Searches for flights from the given origin city to the given destination
   * city, on the given day of the month. If {@code directFlight} is true, it only
   * searches for direct flights, otherwise is searches for direct flights and
   * flights with two "hops." Only searches for up to the number of itineraries
   * given by {@code numberOfItineraries}.
   *
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight        if true, then only search for direct flights,
   *                            otherwise include indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itineraries to return
   *
   * @return If no itineraries were found, return "No flights match your
   *         selection\n". If an error occurs, then return "Failed to search\n".
   *
   *         Otherwise, the sorted itineraries printed in the following format:
   *
   *         Itinerary [itinerary number]: [number of flights] flight(s), [total
   *         flight time] minutes\n [first flight in itinerary]\n ... [last flight
   *         in itinerary]\n
   *
   *         Each flight should be printed using the same format as in the
   *         {@code Flight} class. Itinerary numbers in each search should always
   *         start from 0 and increase by 1.
   *
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity, boolean directFlight, int dayOfMonth,
      int numberOfItineraries) {
    try {
      // WARNING the below code is unsafe and only handles searches for direct flights
      // You can use the below code as a starting reference point or you can get rid
      // of it all and replace it with your own implementation.
      //
      // TODO: YOUR CODE HERE

      StringBuffer sb = new StringBuffer();

      try {
        if (directFlight) {
          sb = getAllDirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries).sb;
        } else {
          Wrapper wrapper = getAllDirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries);
          int itineraryNum = wrapper.itineraryNum;
          sb = wrapper.sb;

          if (itineraryNum < numberOfItineraries) {
            getIndirectFlightsStatement.setInt(1, numberOfItineraries - itineraryNum);
            getIndirectFlightsStatement.setString(2, originCity);
            getIndirectFlightsStatement.setString(3, destinationCity);
            getIndirectFlightsStatement.setInt(4, dayOfMonth);
            getIndirectFlightsStatement.setInt(5, dayOfMonth);

            ResultSet indirectFlights = getIndirectFlightsStatement.executeQuery();

            while (indirectFlights.next()) {
              int result_f1_fid = indirectFlights.getInt("F1_fid");
              int result_f1_dayOfMonth = indirectFlights.getInt("F1_day_of_month");
              String result_f1_carrierId = indirectFlights.getString("F1_carrier_id");
              String result_f1_flightNum = indirectFlights.getString("F1_flight_num");
              String result_f1_originCity = indirectFlights.getString("F1_origin_city");
              String result_f1_destCity = indirectFlights.getString("F1_dest_city");
              int result_f1_time = indirectFlights.getInt("F1_actual_time");
              int result_f1_capacity = indirectFlights.getInt("F1_capacity");
              int result_f1_price = indirectFlights.getInt("F1_price");

              int result_f2_fid = indirectFlights.getInt("F2_fid");
              int result_f2_dayOfMonth = indirectFlights.getInt("F2_day_of_month");
              String result_f2_carrierId = indirectFlights.getString("F2_carrier_id");
              String result_f2_flightNum = indirectFlights.getString("F2_flight_num");
              String result_f2_originCity = indirectFlights.getString("F2_origin_city");
              String result_f2_destCity = indirectFlights.getString("F2_dest_city");
              int result_f2_time = indirectFlights.getInt("F2_actual_time");
              int result_f2_capacity = indirectFlights.getInt("F2_capacity");
              int result_f2_price = indirectFlights.getInt("F2_price");

              sb.append(
                  "Itinerary " + itineraryNum + ": 2 flight(s), " + (result_f1_time + result_f2_time) + " minutes\n");
              sb.append("ID: " + result_f1_fid + " Day: " + result_f1_dayOfMonth + " Carrier: " + result_f1_carrierId
                  + " Number: " + result_f1_flightNum + " Origin: " + result_f1_originCity + " Destination: "
                  + result_f1_destCity + " Duration: " + result_f1_time + " Capacity: " + result_f1_capacity
                  + " Price: " + result_f1_price + "\n");
              sb.append("ID: " + result_f2_fid + " Day: " + result_f2_dayOfMonth + " Carrier: " + result_f2_carrierId
                  + " Number: " + result_f2_flightNum + " Origin: " + result_f2_originCity + " Destination: "
                  + result_f2_destCity + " Duration: " + result_f2_time + " Capacity: " + result_f2_capacity
                  + " Price: " + result_f2_price + "\n");

              itineraryNum++;
            }
          }
        }
      } catch (SQLException e) {
        e.printStackTrace();
      }

      return sb.toString();
    } finally {
      checkDanglingTransaction();
    }
  }

  public Wrapper getAllDirectFlights(String originCity, String destinationCity, int dayOfMonth,
      int numberOfItineraries) {
    StringBuffer sb = new StringBuffer();
    int itineraryNum = 0;

    try {
      getDirectFlightsStatement.setInt(1, numberOfItineraries);
      getDirectFlightsStatement.setString(2, originCity);
      getDirectFlightsStatement.setString(3, destinationCity);
      getDirectFlightsStatement.setInt(4, dayOfMonth);

      ResultSet directFlights = getDirectFlightsStatement.executeQuery();
      while (directFlights.next()) {
        int result_fid = directFlights.getInt("fid");
        int result_dayOfMonth = directFlights.getInt("day_of_month");
        String result_carrierId = directFlights.getString("carrier_id");
        String result_flightNum = directFlights.getString("flight_num");
        String result_originCity = directFlights.getString("origin_city");
        String result_destCity = directFlights.getString("dest_city");
        int result_time = directFlights.getInt("actual_time");
        int result_capacity = directFlights.getInt("capacity");
        int result_price = directFlights.getInt("price");

        sb.append("Itinerary " + itineraryNum + ": 1 flight(s), " + result_time + " minutes\n");
        sb.append("ID: " + result_fid + " Day: " + result_dayOfMonth + " Carrier: " + result_carrierId + " Number: "
            + result_flightNum + " Origin: " + result_originCity + " Destination: " + result_destCity + " Duration: "
            + result_time + " Capacity: " + result_capacity + " Price: " + result_price + "\n");

        itineraryNum++;
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }

    Wrapper wrapper = new Wrapper(itineraryNum, sb);

    return wrapper;
  }

  class Wrapper {
    public int itineraryNum;
    public StringBuffer sb;

    public Wrapper(int itineraryNum, StringBuffer sb) {
      this.itineraryNum = itineraryNum;
      this.sb = sb;
    }
  }

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is
   *                    returned by search in the current session.
   *
   * @return If the user is not logged in, then return "Cannot book reservations,
   *         not logged in\n". If the user is trying to book an itinerary with an
   *         invalid ID or without having done a search, then return "No such
   *         itinerary {@code itineraryId}\n". If the user already has a
   *         reservation on the same day as the one that they are trying to book
   *         now, then return "You cannot book two flights in the same day\n". For
   *         all other errors, return "Booking failed\n".
   *
   *         And if booking succeeded, return "Booked flight(s), reservation ID:
   *         [reservationId]\n" where reservationId is a unique number in the
   *         reservation system that starts from 1 and increments by 1 each time a
   *         successful reservation is made by any user in the system.
   */
  public String transaction_book(int itineraryId) {
    try {
      // TODO: YOUR CODE HERE
      return "Booking failed\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   *
   * @return If no user has logged in, then return "Cannot pay, not logged in\n"
   *         If the reservation is not found / not under the logged in user's
   *         name, then return "Cannot find unpaid reservation [reservationId]
   *         under user: [username]\n" If the user does not have enough money in
   *         their account, then return "User has only [balance] in account but
   *         itinerary costs [cost]\n" For all other errors, return "Failed to pay
   *         for reservation [reservationId]\n"
   *
   *         If successful, return "Paid reservation: [reservationId] remaining
   *         balance: [balance]\n" where [balance] is the remaining balance in the
   *         user's account.
   */
  public String transaction_pay(int reservationId) {
    try {
      // TODO: YOUR CODE HERE
      return "Failed to pay for reservation " + reservationId + "\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not
   *         logged in\n" If the user has no reservations, then return "No
   *         reservations found\n" For all other errors, return "Failed to
   *         retrieve reservations\n"
   *
   *         Otherwise return the reservations in the following format:
   *
   *         Reservation [reservation ID] paid: [true or false]:\n [flight 1 under
   *         the reservation]\n [flight 2 under the reservation]\n Reservation
   *         [reservation ID] paid: [true or false]:\n [flight 1 under the
   *         reservation]\n [flight 2 under the reservation]\n ...
   *
   *         Each flight should be printed using the same format as in the
   *         {@code Flight} class.
   *
   * @see Flight#toString()
   */
  public String transaction_reservations() {
    try {
      // TODO: YOUR CODE HERE
      return "Failed to retrieve reservations\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Implements the cancel operation.
   *
   * @param reservationId the reservation ID to cancel
   *
   * @return If no user has logged in, then return "Cannot cancel reservations,
   *         not logged in\n" For all other errors, return "Failed to cancel
   *         reservation [reservationId]\n"
   *
   *         If successful, return "Canceled reservation [reservationId]\n"
   *
   *         Even though a reservation has been canceled, its ID should not be
   *         reused by the system.
   */
  public String transaction_cancel(int reservationId) {
    try {
      // TODO: YOUR CODE HERE
      return "Failed to cancel reservation " + reservationId + "\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * Example utility function that uses prepared statements
   */
  private int checkFlightCapacity(int fid) throws SQLException {
    checkFlightCapacityStatement.clearParameters();
    checkFlightCapacityStatement.setInt(1, fid);
    ResultSet results = checkFlightCapacityStatement.executeQuery();
    results.next();
    int capacity = results.getInt("capacity");
    results.close();

    return capacity;
  }

  /**
   * Throw IllegalStateException if transaction not completely complete, rollback.
   * 
   */
  private void checkDanglingTransaction() {
    try {
      try (ResultSet rs = tranCountStatement.executeQuery()) {
        rs.next();
        int count = rs.getInt("tran_count");
        if (count > 0) {
          throw new IllegalStateException(
              "Transaction not fully commit/rollback. Number of transaction in process: " + count);
        }
      } finally {
        conn.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Database error", e);
    }
  }

  private static boolean isDeadLock(SQLException ex) {
    return ex.getErrorCode() == 1205;
  }

  /**
   * A class to store flight information.
   */
  class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;

    @Override
    public String toString() {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: " + flightNum + " Origin: "
          + originCity + " Dest: " + destCity + " Duration: " + time + " Capacity: " + capacity + " Price: " + price;
    }
  }

  class User {
    public String username;
    public int balance;

    public User(String username, int balance) {
      this.username = username;
      this.balance = balance;
    }

    @Override
    public String toString() {
      return "username: " + username + " balance: " + balance;
    }
  }
}
