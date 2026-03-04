CREATE SEQUENCE IF NOT EXISTS withdraw_requests_seq START WITH 1 INCREMENT BY 50;

ALTER TABLE WITHDRAW_REQUESTS
    ALTER COLUMN ID SET DEFAULT nextval('withdraw_requests_seq');

SELECT setval(
    'withdraw_requests_seq',
    GREATEST((SELECT COALESCE(MAX(ID), 0) FROM WITHDRAW_REQUESTS) + 1, 1),
    false
);
