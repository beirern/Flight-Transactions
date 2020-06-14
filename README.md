# Flight Reservation System

## What is it?
This java project lets multiple users concurrently book, search, cancel, and pay for flights. Users and their Reservations are stored in the database. The data is based off of real flight data from 2015. More specifics for how each method should work can be found in the assignment details in `./hw5/README.md`. Design descisions I made and how the USERS and RESERVATIONS table is set up can be found in `./hw5/design.md`.

## Requirements

Requires:

1. Connection to a SQL Database (I used Azure)
2. 6 Tables (Can be found and created with `create-tables.sql`):
   
   * FLIGHTS
   * MONTHS
   * WEEKDAYS
   * CARRIERS
   * RESERVATIONS
   * USERS

3. Data for FLIGHTS, MONTHS, WEEKDAYS, CARRIERS (Found in `flight_data.zip` as csv files)
4. dbconn.properties file in `./hw5/dbconn.properties` with 4 properties (example in `./hw5/dbconn.properties.sample`):
    
    * flightapp.server_url: **Enter the server URL.**
    * flightapp.database_name: **Enter your database name.**
    * flightapp.username: **Enter the admin username of your server.**
    * flightapp.password: **Add your admin password.**

5. Maven

## How to Run

Run either:

1. `cd ./hw5/`
2. 2 commands:
   1. `$ mvn clean compile assembly:single`  
   2. `$ java -jar target/FlightApp-1.0-jar-with-dependencies.jar`
3. 1 command:
   1. `$ mvn compile exec:java`