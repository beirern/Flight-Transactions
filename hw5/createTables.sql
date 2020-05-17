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
END;