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

  private static final String CLEAR_USERS_TABLE = "DELETE FROM USERS";
  private PreparedStatement clearUsersTableStatement;

  private static final String CLEAR_RESERVATOINS_TABLE = "DELETE FROM RESERVATIONS";
  private PreparedStatement clearReservationsTableStatement;

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
      + "AND F1.day_of_month = ? AND F2.day_of_month = ? AND F2.canceled = 0 ORDER BY F1.actual_time + F2.actual_time ASC, F1.fid ASC, F2.fid ASC";
  private PreparedStatement getIndirectFlightsStatement;

  private static final String GET_RESERVATIONS = "SELECT * FROM RESERVATIONS WHERE userid = ?";
  private PreparedStatement getReservationsStatement;

  private static final String GET_SINGLE_FLIGHT = "SELECT fid, day_of_month, carrier_id, flight_num, origin_city, dest_city, actual_time, capacity, price "
      + "FROM FLIGHTS WHERE fid = ?";
  private PreparedStatement getSingleFlightStatement;

  private static final String GET_ALL_FLIGHT_CAPACITIES = "WITH FlightOne(flight1, count) AS (SELECT flight1, Count(flight1) FROM RESERVATIONS GROUP BY flight1), "
      + "FlightTwo(flight2, count) AS (SELECT flight2, Count(flight2) FROM RESERVATIONS GROUP BY flight2) "
      + "SELECT FO.flight1 AS fid1, FO.count AS count1, FT.flight2 AS fid2, FT.count AS count2 FROM FlightOne AS FO, FlightTwo AS FT";
  private PreparedStatement getAllFlightCapacities;

  private static final String GET_RESERVATION_ID = "SELECT Count(*) AS count FROM RESERVATIONS";
  private PreparedStatement getReservationIdStatement;

  private static final String CREATE_RESERVATION = "INSERT INTO RESERVATIONS (id, userid, flight1, flight2, paid, cancelled) VALUES (?, ?, ?, ?, ?, ?)";
  private PreparedStatement createReservationStatement;

  private static final String PAY_RESERVATION = "UPDATE RESERVATIONS SET paid = 1 WHERE id = ?";
  private PreparedStatement payReservationStatement;

  private static final String UPDATE_BALANCE = "UPDATE USERS SET balance = ? WHERE username = ?";
  private PreparedStatement updateBalanceStatement;

  private User user;
  private List<Flight> directFlights;
  private List<List<Flight>> totalFlightsList;

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
      clearReservationsTableStatement.executeUpdate();
      clearReservationsTableStatement.close();

      clearUsersTableStatement.executeUpdate();
      clearUsersTableStatement.close();
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
    clearUsersTableStatement = conn.prepareStatement(CLEAR_USERS_TABLE);
    clearReservationsTableStatement = conn.prepareStatement(CLEAR_RESERVATOINS_TABLE);
    createUserStatement = conn.prepareStatement(CREATE_USER);
    findUserStatement = conn.prepareStatement(FIND_USER);
    getDirectFlightsStatement = conn.prepareStatement(GET_DIRECT_FLIGHTS);
    getIndirectFlightsStatement = conn.prepareStatement(GET_INDIRECT_FLIGHTS);
    getReservationsStatement = conn.prepareStatement(GET_RESERVATIONS);
    getSingleFlightStatement = conn.prepareStatement(GET_SINGLE_FLIGHT);
    getAllFlightCapacities = conn.prepareStatement(GET_ALL_FLIGHT_CAPACITIES);
    createReservationStatement = conn.prepareStatement(CREATE_RESERVATION);
    payReservationStatement = conn.prepareStatement(PAY_RESERVATION);
    getReservationIdStatement = conn.prepareStatement(GET_RESERVATION_ID);
    updateBalanceStatement = conn.prepareStatement(UPDATE_BALANCE);
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
      if (user != null) {
        return "User already logged in\n";
      }

      findUserStatement.clearParameters();
      findUserStatement.setString(1, username.toLowerCase());
      ResultSet userSet = findUserStatement.executeQuery();

      if (!userSet.next()) {
        return "Login failed\n";
      }

      byte[] salt = userSet.getBytes(3);

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

      if (!Arrays.equals(userSet.getBytes(2), hash)) {
        return "Login failed\n";
      }

      user = new User(userSet.getString(1), userSet.getInt(4));
      userSet.close();

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

      createUserStatement.clearParameters();
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
      StringBuffer sb = new StringBuffer();
      directFlights = new ArrayList<>();
      totalFlightsList = new ArrayList<>();

      try {
        directFlights = getAllDirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries);
      } catch (Exception e) {
        e.printStackTrace();
      }

      if (directFlight) {
        for (int i = 0; i < directFlights.size(); i++) {
          sb.append("Itinerary " + i + ": 1 flight(s), " + directFlights.get(i).time + " minutes\n");
          sb.append(directFlights.get(i));
        }

        totalFlightsList.clear();
      } else {
        try {
          if (directFlights.size() < numberOfItineraries) {
            getIndirectFlightsStatement.clearParameters();
            getIndirectFlightsStatement.setInt(1, numberOfItineraries - directFlights.size());
            getIndirectFlightsStatement.setString(2, originCity);
            getIndirectFlightsStatement.setString(3, destinationCity);
            getIndirectFlightsStatement.setInt(4, dayOfMonth);
            getIndirectFlightsStatement.setInt(5, dayOfMonth);

            ResultSet indirectFlights = getIndirectFlightsStatement.executeQuery();

            List<List<Flight>> indirectFlightsList = new ArrayList<List<Flight>>();

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

              Flight flight1 = new Flight(result_f1_fid, result_f1_dayOfMonth, result_f1_carrierId, result_f1_flightNum,
                  result_f1_originCity, result_f1_destCity, result_f1_time, result_f1_capacity, result_f1_price);

              int result_f2_fid = indirectFlights.getInt("F2_fid");
              int result_f2_dayOfMonth = indirectFlights.getInt("F2_day_of_month");
              String result_f2_carrierId = indirectFlights.getString("F2_carrier_id");
              String result_f2_flightNum = indirectFlights.getString("F2_flight_num");
              String result_f2_originCity = indirectFlights.getString("F2_origin_city");
              String result_f2_destCity = indirectFlights.getString("F2_dest_city");
              int result_f2_time = indirectFlights.getInt("F2_actual_time");
              int result_f2_capacity = indirectFlights.getInt("F2_capacity");
              int result_f2_price = indirectFlights.getInt("F2_price");

              Flight flight2 = new Flight(result_f2_fid, result_f2_dayOfMonth, result_f2_carrierId, result_f2_flightNum,
                  result_f2_originCity, result_f2_destCity, result_f2_time, result_f2_capacity, result_f2_price);

              indirectFlightsList.add(new ArrayList<>());
              indirectFlightsList.get(indirectFlightsList.size() - 1).add(flight1);
              indirectFlightsList.get(indirectFlightsList.size() - 1).add(flight2);
            }

            int directFlightsIndex = 0;
            int indirectFlightsIndex = 0;
            for (int i = 0; i < numberOfItineraries; i++) {
              if (directFlightsIndex < directFlights.size() && indirectFlightsIndex < indirectFlightsList.size()) {
                if (directFlights
                    .get(directFlightsIndex).time < indirectFlightsList.get(indirectFlightsIndex).get(0).time
                        + indirectFlightsList.get(indirectFlightsIndex).get(1).time) {
                  sb.append(
                      "Itinerary " + i + ": 1 flight(s), " + directFlights.get(directFlightsIndex).time + " minutes\n");
                  sb.append(directFlights.get(directFlightsIndex));

                  totalFlightsList.add(new ArrayList<Flight>());
                  totalFlightsList.get(totalFlightsList.size() - 1).add(directFlights.get(directFlightsIndex));
                  directFlightsIndex++;
                } else if (directFlights
                    .get(directFlightsIndex).time > indirectFlightsList.get(indirectFlightsIndex).get(0).time
                        + indirectFlightsList.get(indirectFlightsIndex).get(1).time) {
                  sb.append(
                      "Itinerary " + i + ": 2 flight(s), " + (indirectFlightsList.get(indirectFlightsIndex).get(0).time
                          + indirectFlightsList.get(indirectFlightsIndex).get(1).time) + " minutes\n");
                  sb.append(indirectFlightsList.get(indirectFlightsIndex).get(0));
                  sb.append(indirectFlightsList.get(indirectFlightsIndex).get(1));

                  totalFlightsList.add(new ArrayList<Flight>());
                  totalFlightsList.get(totalFlightsList.size() - 1)
                      .add(indirectFlightsList.get(indirectFlightsIndex).get(0));
                  totalFlightsList.get(totalFlightsList.size() - 1)
                      .add(indirectFlightsList.get(indirectFlightsIndex).get(1));
                  indirectFlightsIndex++;
                } else {
                  if (directFlights.get(directFlightsIndex).fid < indirectFlightsList.get(indirectFlightsIndex)
                      .get(0).fid) {
                    sb.append("Itinerary " + i + ": 1 flight(s), " + directFlights.get(directFlightsIndex).time
                        + " minutes\n");
                    sb.append(directFlights.get(directFlightsIndex));

                    totalFlightsList.add(new ArrayList<Flight>());
                    totalFlightsList.get(totalFlightsList.size() - 1).add(directFlights.get(directFlightsIndex));
                    directFlightsIndex++;
                  } else {
                    sb.append("Itinerary " + i + ": 2 flight(s), "
                        + (indirectFlightsList.get(indirectFlightsIndex).get(0).time
                            + indirectFlightsList.get(indirectFlightsIndex).get(1).time)
                        + " minutes\n");
                    sb.append(indirectFlightsList.get(indirectFlightsIndex).get(0));
                    sb.append(indirectFlightsList.get(indirectFlightsIndex).get(1));

                    totalFlightsList.add(new ArrayList<Flight>());
                    totalFlightsList.get(totalFlightsList.size() - 1)
                        .add(indirectFlightsList.get(indirectFlightsIndex).get(0));
                    totalFlightsList.get(totalFlightsList.size() - 1)
                        .add(indirectFlightsList.get(indirectFlightsIndex).get(1));
                    indirectFlightsIndex++;
                  }
                }
              } else if (directFlightsIndex < directFlights.size()) {
                sb.append(
                    "Itinerary " + i + ": 1 flight(s), " + directFlights.get(directFlightsIndex).time + " minutes\n");
                sb.append(directFlights.get(directFlightsIndex));

                totalFlightsList.add(new ArrayList<Flight>());
                totalFlightsList.get(totalFlightsList.size() - 1).add(directFlights.get(directFlightsIndex));
                directFlightsIndex++;
              } else if (indirectFlightsIndex < indirectFlightsList.size()) {
                sb.append(
                    "Itinerary " + i + ": 2 flight(s), " + (indirectFlightsList.get(indirectFlightsIndex).get(0).time
                        + indirectFlightsList.get(indirectFlightsIndex).get(1).time) + " minutes\n");
                sb.append(indirectFlightsList.get(indirectFlightsIndex).get(0));
                sb.append(indirectFlightsList.get(indirectFlightsIndex).get(1));

                totalFlightsList.add(new ArrayList<Flight>());
                totalFlightsList.get(totalFlightsList.size() - 1)
                    .add(indirectFlightsList.get(indirectFlightsIndex).get(0));
                totalFlightsList.get(totalFlightsList.size() - 1)
                    .add(indirectFlightsList.get(indirectFlightsIndex).get(1));
                indirectFlightsIndex++;
              }
            }

            directFlights.clear();
          } else {
            for (int i = 0; i < directFlights.size(); i++) {
              sb.append("Itinerary " + i + ": 1 flight(s), " + directFlights.get(i).time + " minutes\n");
              sb.append(directFlights.get(i));
            }
          }
          totalFlightsList.clear();
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }

      return sb.toString();
    } finally {
      checkDanglingTransaction();
    }
  }

  public List<Flight> getAllDirectFlights(String originCity, String destinationCity, int dayOfMonth,
      int numberOfItineraries) {

    List<Flight> list = new ArrayList<>();
    try {
      getDirectFlightsStatement.clearParameters();
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

        Flight flight = new Flight(result_fid, result_dayOfMonth, result_carrierId, result_flightNum, result_originCity,
            result_destCity, result_time, result_capacity, result_price);

        list.add(flight);
      }
      directFlights.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }

    return list;
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
      if (user == null) {
        return "Cannot book reservations, not logged in\n";
      }

      if (totalFlightsList == null || directFlights == null) {
        return "No such itinerary " + itineraryId + "\n";
      }

      if (totalFlightsList.size() == 0 && directFlights.size() == 0) {
        return "No such itinerary " + itineraryId + "\n";
      }

      if (totalFlightsList.size() > 0 && itineraryId > totalFlightsList.size() - 1) {
        return "No such itinerary " + itineraryId + "\n";
      }

      if (directFlights.size() > 0 && itineraryId > directFlights.size() - 1) {
        return "No such itinerary " + itineraryId + "\n";
      }

      List<Flight> flights = new ArrayList<>();

      if (directFlights.size() > 0) {
        flights.add(directFlights.get(itineraryId));
      } else {
        for (Flight f : totalFlightsList.get(itineraryId)) {
          flights.add(f);
        }
      }

      List<Reservation> reservations = new ArrayList<>();
      int id = 0;
      try {
        conn.setAutoCommit(false);
        try {
          getReservationsStatement.clearParameters();
          getReservationsStatement.setString(1, user.username.toLowerCase());

          ResultSet allReservations = getReservationsStatement.executeQuery();
          while (allReservations.next()) {
            int rid = allReservations.getInt("id");
            int flightOneId = allReservations.getInt("flight1");
            int flightTwoId = allReservations.getInt("flight2");
            int paid = allReservations.getInt("paid");
            int cancelled = allReservations.getInt("cancelled");

            Flight flightOne = null;
            Flight flightTwo = null;

            try {
              getSingleFlightStatement.clearParameters();
              getSingleFlightStatement.setInt(1, flightOneId);

              ResultSet rs = getSingleFlightStatement.executeQuery();
              rs.next();

              int result_fid = rs.getInt("fid");
              int result_dayOfMonth = rs.getInt("day_of_month");
              String result_carrierId = rs.getString("carrier_id");
              String result_flightNum = rs.getString("flight_num");
              String result_originCity = rs.getString("origin_city");
              String result_destCity = rs.getString("dest_city");
              int result_time = rs.getInt("actual_time");
              int result_capacity = rs.getInt("capacity");
              int result_price = rs.getInt("price");

              flightOne = new Flight(result_fid, result_dayOfMonth, result_carrierId, result_flightNum,
                  result_originCity, result_destCity, result_time, result_capacity, result_price);
            } catch (Exception e) {
              conn.rollback();
              conn.setAutoCommit(true);
              System.out.println("First");
              return "Booking failed\n";
            }

            if (flightTwoId != 0) {
              try {
                getSingleFlightStatement.clearParameters();
                getSingleFlightStatement.setInt(1, flightTwoId);

                ResultSet rs = getSingleFlightStatement.executeQuery();
                rs.next();

                int result_fid = rs.getInt("fid");
                int result_dayOfMonth = rs.getInt("day_of_month");
                String result_carrierId = rs.getString("carrier_id");
                String result_flightNum = rs.getString("flight_num");
                String result_originCity = rs.getString("origin_city");
                String result_destCity = rs.getString("dest_city");
                int result_time = rs.getInt("actual_time");
                int result_capacity = rs.getInt("capacity");
                int result_price = rs.getInt("price");

                flightTwo = new Flight(result_fid, result_dayOfMonth, result_carrierId, result_flightNum,
                    result_originCity, result_destCity, result_time, result_capacity, result_price);
              } catch (Exception e) {
                conn.rollback();
                conn.setAutoCommit(true);
                System.out.println("Second");
                return "Booking failed\n";
              }
            }

            reservations.add(new Reservation(rid, flightOne, flightTwo, paid, cancelled));
          }
        } catch (Exception e) {
          conn.rollback();
          conn.setAutoCommit(true);
          System.out.println("Third");
          return "Booking failed\n";
        }

        for (Reservation r : reservations) {
          if (r.flightOne.dayOfMonth == flights.get(0).dayOfMonth) {
            conn.rollback();
            conn.setAutoCommit(true);
            return "You cannot book two flights in the same day\n";
          }
        }

        Map<Integer, Integer> flightCapacities = new HashMap<>();
        try {
          getAllFlightCapacities.clearParameters();
          ResultSet rs = getAllFlightCapacities.executeQuery();

          while (rs.next()) {
            if (rs.getInt("fid1") != 0) {
              flightCapacities.put(rs.getInt("fid1"), rs.getInt("count1"));
            }
            if (rs.getInt("fid2") != 0) {
              flightCapacities.put(rs.getInt("fid2"), rs.getInt("count2"));
            }
          }

          for (Flight f : flights) {
            if (flightCapacities.containsKey(f.fid)) {
              if (flightCapacities.get(f.fid) + 1 > f.capacity) {
                conn.rollback();
                conn.setAutoCommit(true);
                System.out.println("Fourth");
                return "Booking failed\n";
              }
            }
          }
        } catch (Exception e) {
          conn.rollback();
          conn.setAutoCommit(true);
          System.out.println("Fifth");
          return "Booking failed\n";
        }

        try {
          getReservationIdStatement.clearParameters();
          ResultSet counts = getReservationIdStatement.executeQuery();
          counts.next();

          id = counts.getInt("count") + 1;
        } catch (SQLException e) {
          conn.rollback();
          conn.setAutoCommit(true);
          System.out.println("Six");
          return "Booking failed\n";
        }

        try {
          createReservationStatement.clearParameters();
          createReservationStatement.setInt(1, id);
          createReservationStatement.setString(2, user.username.toLowerCase());
          createReservationStatement.setInt(3, flights.get(0).fid);
          createReservationStatement.setInt(5, 0);
          createReservationStatement.setInt(6, 0);

          if (flights.size() == 2) {
            createReservationStatement.setInt(4, flights.get(1).fid);
          } else {
            createReservationStatement.setNull(4, java.sql.Types.INTEGER);
          }

          createReservationStatement.execute();
        } catch (SQLException e) {
          System.out.println(e.getMessage());
          conn.rollback();
          conn.setAutoCommit(true);
          if (e.getMessage().contains("deadlock")) {
            return transaction_book(itineraryId);
          }
          System.out.println("Seventh");
          return "Booking failed\n";
        }
        conn.commit();
        conn.setAutoCommit(true);
      } catch (SQLException e) {
        try {
          conn.rollback();
          conn.setAutoCommit(true);
          if (e.getMessage().contains("deadlock")) {
            return transaction_book(itineraryId);
          }
        } catch (SQLException e2) {
          e2.printStackTrace();
        }
        System.out.println("Eighth");
        return "Booking failed\n";
      }
      return "Booked flight(s), reservation ID: " + id + "\n";
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
      if (user == null) {
        return "Cannot pay, not logged in\n";
      }

      int fid1 = 0;
      int fid2 = 0;
      int paid = 0;
      try {
        conn.setAutoCommit(false);
        try {
          getReservationsStatement.clearParameters();
          getReservationsStatement.setString(1, user.username.toLowerCase());

          ResultSet reservationsSet = getReservationsStatement.executeQuery();

          boolean found = false;
          while (reservationsSet.next()) {
            if (reservationsSet.getInt("id") == reservationId) {
              found = true;
              fid1 = reservationsSet.getInt("flight1");
              fid2 = reservationsSet.getInt("flight2");
              paid = reservationsSet.getInt("paid");
            }
          }
          if (!found || paid == 1) {
            conn.rollback();
            conn.setAutoCommit(true);
            return "Cannot find unpaid reservation " + reservationId + " under user: " + user.username + "\n";
          }

        } catch (SQLException e) {
          conn.rollback();
          conn.setAutoCommit(true);
          return "Failed to pay for reservation " + reservationId + "\n";
        }

        int price1 = 0;
        try {
          getSingleFlightStatement.clearParameters();
          getSingleFlightStatement.setInt(1, fid1);

          ResultSet flight = getSingleFlightStatement.executeQuery();
          flight.next();
          price1 = flight.getInt("price");
        } catch (SQLException e) {
          conn.rollback();
          conn.setAutoCommit(true);
          return "Failed to pay for reservation " + reservationId + "\n";
        }

        int price2 = 0;
        if (fid2 != 0) {
          try {
            getSingleFlightStatement.clearParameters();
            getSingleFlightStatement.setInt(1, fid2);

            ResultSet flight = getSingleFlightStatement.executeQuery();
            flight.next();
            price2 = flight.getInt("price");
          } catch (SQLException e) {
            conn.rollback();
            conn.setAutoCommit(true);
            return "Failed to pay for reservation " + reservationId + "\n";
          }
        }

        if (user.balance < price1 + price2) {
          conn.rollback();
          conn.setAutoCommit(true);
          return "User has only " + user.balance + " in account but itinerary costs " + (price1 + price2) + "\n";
        }

        try {
          payReservationStatement.clearParameters();
          payReservationStatement.setInt(1, reservationId);

          payReservationStatement.executeUpdate();
        } catch (SQLException e) {
          conn.rollback();
          conn.setAutoCommit(true);
          return "Failed to pay for reservation " + reservationId + "\n";
        }

        user.balance -= (price1 + price2);
        try {
          updateBalanceStatement.clearParameters();
          updateBalanceStatement.setInt(1, user.balance);
          updateBalanceStatement.setString(2, user.username);

          updateBalanceStatement.executeUpdate();
        } catch (SQLException e) {
          conn.rollback();
          conn.setAutoCommit(true);
          return "Failed to pay for reservation " + reservationId + "\n";
        }
        conn.commit();
        conn.setAutoCommit(true);
      } catch (SQLException e) {
        try {
          conn.rollback();
          conn.setAutoCommit(true);
        } catch (SQLException e2) {
          e2.printStackTrace();
        }
        return "Failed to pay for reservation " + reservationId + "\n";
      }
      return "Paid reservation: " + reservationId + " remaining balance: " + user.balance + "\n";
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
      if (user == null) {
        return "Cannot view reservations, not logged in\n";
      }

      StringBuffer sb = new StringBuffer();
      try {
        conn.setAutoCommit(false);
        try {
          getReservationsStatement.clearParameters();
          getReservationsStatement.setString(1, user.username.toLowerCase());

          ResultSet reservations = getReservationsStatement.executeQuery();
          if (!reservations.next()) {
            conn.rollback();
            conn.setAutoCommit(true);
            return "No reservations found\n";
          }

          do {
            int reservationId = reservations.getInt("id");

            int fid1 = reservations.getInt("flight1");
            int fid2 = reservations.getInt("flight2");
            int paid = reservations.getInt("paid");
            int cancelled = reservations.getInt("cancelled");

            Flight flight1 = null;
            try {
              getSingleFlightStatement.clearParameters();
              getSingleFlightStatement.setInt(1, fid1);

              ResultSet flight = getSingleFlightStatement.executeQuery();
              flight.next();

              int dayOfMonth = flight.getInt("day_of_month");
              String carrierId = flight.getString("carrier_id");
              String flightNum = flight.getString("flight_num");
              String originCity = flight.getString("origin_city");
              String destCity = flight.getString("dest_city");
              int time = flight.getInt("actual_time");
              int capacity = flight.getInt("capacity");
              int price = flight.getInt("price");
              flight1 = new Flight(fid1, dayOfMonth, carrierId, flightNum, originCity, destCity, time, capacity, price);
            } catch (SQLException e) {
              conn.rollback();
              conn.setAutoCommit(true);
              return "Failed to retrieve reservations\n";
            }

            Flight flight2 = null;
            if (fid2 != 0) {
              try {
                getSingleFlightStatement.clearParameters();
                getSingleFlightStatement.setInt(1, fid2);

                ResultSet flight = getSingleFlightStatement.executeQuery();
                flight.next();

                int dayOfMonth = flight.getInt("day_of_month");
                String carrierId = flight.getString("carrier_id");
                String flightNum = flight.getString("flight_num");
                String originCity = flight.getString("origin_city");
                String destCity = flight.getString("dest_city");
                int time = flight.getInt("actual_time");
                int capacity = flight.getInt("capacity");
                int price = flight.getInt("price");
                flight2 = new Flight(fid2, dayOfMonth, carrierId, flightNum, originCity, destCity, time, capacity,
                    price);
              } catch (SQLException e) {
                conn.rollback();
                conn.setAutoCommit(true);
                return "Failed to retrieve reservations\n";
              }
            }

            sb.append(new Reservation(reservationId, flight1, flight2, paid, cancelled));
          } while (reservations.next());
        } catch (SQLException e) {
          conn.rollback();
          conn.setAutoCommit(true);
          return "Failed to retrieve reservations\n";
        }
        conn.commit();
        conn.setAutoCommit(true);
      } catch (SQLException e) {
        try {
          conn.rollback();
          conn.setAutoCommit(true);
          return "Failed to retrieve reservations\n";
        } catch (SQLException e2) {
          e.printStackTrace();
        }
      }
      return sb.toString();
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

    public Flight(int fid, int day, String carrier, String number, String origin, String destination, int duration,
        int capacity, int price) {
      this.fid = fid;
      this.dayOfMonth = day;
      this.carrierId = carrier;
      this.flightNum = number;
      this.originCity = origin;
      this.destCity = destination;
      this.time = duration;
      this.capacity = capacity;
      this.price = price;
    }

    @Override
    public String toString() {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: " + flightNum + " Origin: "
          + originCity + " Dest: " + destCity + " Duration: " + time + " Capacity: " + capacity + " Price: " + price
          + "\n";
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

  class Reservation {
    public int id;
    public Flight flightOne;
    public Flight flightTwo;
    public boolean paid;
    public boolean canceled;

    public Reservation(int id, Flight flightOne, Flight flightTwo, int paid, int canceled) {
      this.id = id;
      this.flightOne = flightOne;
      this.flightTwo = flightTwo;
      this.paid = (paid == 1);
      this.canceled = (canceled == 1);
    }

    @Override
    public String toString() {
      if (flightTwo != null) {
        return "Reservation " + id + " paid: " + paid + ":\n" + flightOne + flightTwo;
      }
      return "Reservation " + id + " paid: " + paid + ":\n" + flightOne;
    }
  }
}
