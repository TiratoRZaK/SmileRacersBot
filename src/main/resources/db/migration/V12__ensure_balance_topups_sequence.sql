CREATE SEQUENCE IF NOT EXISTS balance_topups_seq START WITH 1 INCREMENT BY 50;

ALTER TABLE BALANCE_TOPUPS
    ALTER COLUMN ID SET DEFAULT nextval('balance_topups_seq');

SELECT setval(
    'balance_topups_seq',
    GREATEST((SELECT COALESCE(MAX(ID), 0) FROM BALANCE_TOPUPS) + 1, 1),
    false
);
