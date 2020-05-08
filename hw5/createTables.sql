CREATE TABLE USERS
(
    username VARCHAR(20) NOT NULL PRIMARY KEY,
    password VARBINARY(20) NOT NULL,
    balance INT NOT NULL,
);

CREATE TABLE RESERVATIONS
(
    id INT IDENTITY(1,1) PRIMARY KEY,
    flight_one INT NOT NULL,
    flight_two INT,
    username VARCHAR(20) NOT NULL REFERENCES USERS(username),
    paid INT,
    cancelled INT
);