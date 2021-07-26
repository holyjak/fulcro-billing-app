CREATE TABLE AGREEMENT (
  ORG_NO varchar not null primary key,
  name varchar not null);

CREATE TABLE invoice (
  id varchar not null primary key,
  ORGANIZATION_NUMBER varchar not null,
  CREATED_DATE date not null,
  billing_date date not null,
  cache_done NUMBER(1) default 0,
  timestamp timestamp  not null,
  invoice_period_from TIMESTAMP not null,
  invoice_period_to TIMESTAMP not null,
  no_usage_data NUMBER(1) default 0,
  notification_sent NUMBER(1) default 0,
  employee_notification_sent NUMBER(1) default 0,
  valid NUMBER(1) default 1);

CREATE TABLE INVOICE_EMPLOYEE (
  INVOICE_ID varchar not null,
  EMPLOYEE_ID varchar not null,
  ACCOUNT_ID varchar not null,
  TOTAL_USAGE_INC_VAT decimal(20,2) not null,
  PRIMARY KEY(INVOICE_ID, EMPLOYEE_ID));

CREATE TABLE INVOICE_USAGE (
  INVOICE_ID varchar not null,
  EMPLOYEE_ID varchar not null,
  CATEGORY varchar default 'LEASING_COST',
  USAGE_INC_VAT decimal(20,2) not null,
  PRIMARY KEY(INVOICE_ID, EMPLOYEE_ID));

CREATE TABLE invoice_error (
  organization_number varchar not null primary key,
  latest_error varchar not null,
  created TIMESTAMP not null);

CREATE TABLE ORGANIZATION (
  ORGANIZATION_NUMBER varchar not null primary key,
  INVOICE_TS DATE not null, -- => `DATE '2001-01-31'`
  CURRENT_BILL_CYCLE_LENGTH varchar not null,
  VALIDATION_ERRORS varchar);

INSERT INTO ORGANIZATION(ORGANIZATION_NUMBER, INVOICE_TS, CURRENT_BILL_CYCLE_LENGTH, VALIDATION_ERRORS)
VALUES
('123456789', DATEADD(DAY, -7, current_date), 'MONTH', 'Some employees missing dept.id'),
('234567890', DATEADD(MONTH, -1, current_date), 'MONTH', NULL),
('333333333', DATEADD(DAY, -40, current_date), 'QUARTER', NULL);

--INSERT INTO invoice (id, ORGANIZATION_NUMBER, CREATED_DATE, billing_date, timestamp, invoice_period_from, invoice_period_to) VALUES
--('inv003', '123456789', DATEADD(DAY, -1, current_date)),
--('inv002', '123456789', DATEADD(MONTH, -1, current_date)),
--('inv001', '123456789', DATEADD(MONTH, -2, current_date)),
--
--('inv203', '234567890', DATEADD(MONTH, -1, current_date)),
--('inv202', '234567890', DATEADD(MONTH, -2, current_date)),
--('inv201', '234567890', DATEADD(MONTH, -3, current_date)),
--
--('inv303', '333333333', DATEADD(DAY, -40, current_date)),
--('inv302', '333333333', DATEADD(MONTH, -4, current_date)),
--('inv301', '333333333', DATEADD(MONTH, -7, current_date));
--
--INSERT INTO INVOICE_EMPLOYEE (INVOICE_ID, EMPLOYEE_ID, ACCOUNT_ID, TOTAL_USAGE_INC_VAT) VALUES
--('inv003', 'e11', 'a1', 100.00),
--('inv003', 'e12', 'a1', 512.35),
--('inv003', 'e13', 'a2', 23.25),
--('inv003', 'e14', 'a2', 50.50),
--('inv003', 'e15', 'a3', 75.15),
--
--('inv203', 'e21', 'a2.X', 100.00),
--('inv203', 'e22', 'a2.X', 512.35),
--('inv203', 'e23', 'a2.Z', 23.25),
--
--('inv303', 'e31', '111', 85.00),
--('inv303', 'e32', '222', 768.43),
--('inv303', 'e33', '222', 42.42);
