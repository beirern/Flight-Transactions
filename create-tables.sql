IF (NOT EXISTS (SELECT *
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_NAME = 'USERS'))

BEGIN
    CREATE TABLE USERS
    (
        username VARCHAR(20) NOT NULL PRIMARY KEY,
        password VARBINARY(128) NOT NULL,
        salt VARBINARY(128) NOT NULL,
        balance INT NOT NULL,
    )

    CREATE TABLE RESERVATIONS
    (
        id INT PRIMARY KEY,
        userid VARCHAR(20) FOREIGN KEY REFERENCES USERS(username),
        flight1 INT NOT NULL,
        flight2 INT,
        paid INT NOT NULL,
        cancelled INT NOT NULL
    )

    CREATE TABLE CARRIERS
    (
        cid VARCHAR(7) NOT NULL PRIMARY KEY,
        name VARCHAR(83)
    )

    CREATE TABLE MONTHS
    (
        mid INTEGER NOT NULL PRIMARY KEY,
        month VARCHAR(9)
    )

    CREATE TABLE WEEKDAYS
    (
        did INTEGER NOT NULL PRIMARY KEY,
        day_of_week VARCHAR(9)
    )

    CREATE TABLE FLIGHTS
    (
        fid INTEGER NOT NULL PRIMARY KEY,
        month_id INTEGER REFERENCES MONTHS(mid),
        day_of_month INTEGER,
        day_of_week_id INTEGER REFERENCES WEEKDAYS(did),
        carrier_id VARCHAR(7) REFERENCES CARRIERS(cid),
        flight_num INTEGER,
        origin_city VARCHAR(34),
        origin_state VARCHAR(47),
        dest_city VARCHAR(34),
        dest_state VARCHAR(46),
        departure_delay INTEGER,
        taxi_out INTEGER,
        arrival_delay INTEGER,
        canceled INTEGER,
        actual_time INTEGER,
        distance INTEGER,
        capacity INTEGER,
        price INTEGER
    )
END;