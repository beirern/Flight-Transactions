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
END