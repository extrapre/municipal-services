CREATE TABLE public.nulm_susv_transaction_detail (
	uuid varchar(64) NOT NULL,
	transaction_type varchar(64) NOT NULL,
	amount varchar(64) NULL,
	mode_of_payment varchar(64) NULL,
	payment_details varchar(255) NULL,
	donation_received_from varchar(255) NULL,
	donor_details varchar(20) NULL,
	expenditure_type varchar(255) NULL,
	expenditure_details varchar(255) NULL,
	email_id varchar(255) NULL,
	"comments" varchar(255) NULL,
	tenant_id varchar(256) NULL,
	remark varchar(256) NULL,
	is_active bool NULL,
	created_by varchar(64) NULL,
	created_time int8 NULL,
	last_modified_by varchar(64) NULL,
	last_modified_time int8 NULL,
	CONSTRAINT nulm_susv_transaction_detail_pkey PRIMARY KEY (uuid)
);