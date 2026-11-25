create table accounts (
    id uuid primary key,
    email varchar(254) not null unique,
    password_hash varchar(100) not null,
    display_name varchar(100) not null,
    role varchar(20) check (role in ('FOUNDER', 'OWNER', 'STUDENT')),
    founder_budget bigint check (founder_budget >= 0),
    business_number varchar(10),
    business_status_checked_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    check (role <> 'FOUNDER' or founder_budget is not null),
    check (role <> 'OWNER' or (business_number is not null and business_status_checked_at is not null))
);

create table access_tokens (
    token_hash varchar(64) primary key,
    account_id uuid not null references accounts(id),
    expires_at timestamp with time zone not null
);
create index access_tokens_account_idx on access_tokens(account_id);
create index access_tokens_expiration_idx on access_tokens(expires_at);

create table student_profiles (
    account_id uuid primary key references accounts(id),
    university varchar(160) not null,
    major varchar(120),
    academic_year int check (academic_year between 1 and 8),
    interests varchar(500) not null,
    availability varchar(1000) not null,
    advisor varchar(120),
    linked_course varchar(150)
);

create table student_documents (
    id uuid primary key,
    account_id uuid not null references student_profiles(account_id),
    document_type varchar(25) not null check (document_type in ('ENROLLMENT', 'TRANSCRIPT', 'PORTFOLIO')),
    filename varchar(150) not null,
    media_type varchar(50) not null,
    contents bytea not null,
    uploaded_at timestamp with time zone not null default current_timestamp,
    unique(account_id, document_type)
);

create table buildings (
    id uuid primary key,
    building_key varchar(100) not null unique,
    name varchar(200),
    road_address varchar(300) not null,
    longitude decimal(11, 8) not null check (longitude between -180 and 180),
    latitude decimal(10, 8) not null check (latitude between -90 and 90),
    main_use varchar(150),
    source varchar(250) not null,
    registered_at timestamp with time zone not null default current_timestamp
);

create table vacancies (
    id uuid primary key,
    building_id uuid not null references buildings(id),
    unit_label varchar(100) not null,
    area_m2 decimal(12, 2) check (area_m2 > 0),
    deposit_krw bigint check (deposit_krw >= 0),
    monthly_rent_krw bigint check (monthly_rent_krw >= 0),
    status varchar(20) not null check (status in ('UNVERIFIED', 'AVAILABLE', 'IN_NEGOTIATION', 'CLOSED')),
    source varchar(250) not null,
    verified_at timestamp with time zone,
    unique(building_id, unit_label),
    unique(id, building_id),
    check (status = 'UNVERIFIED' or verified_at is not null)
);

create table building_opinions (
    id uuid primary key,
    building_id uuid not null references buildings(id),
    author_id uuid not null references accounts(id),
    desired_business varchar(160) not null,
    reason varchar(1500) not null,
    created_at timestamp with time zone not null default current_timestamp
);
create index building_opinions_recent_idx on building_opinions(building_id, created_at);

create table startup_plans (
    id uuid primary key,
    founder_id uuid not null references accounts(id),
    business_idea varchar(500),
    preferred_building_id uuid references buildings(id),
    preferred_address varchar(300),
    selected_building_id uuid references buildings(id),
    selected_vacancy_id uuid,
    selected_address varchar(300),
    selected_industry varchar(160),
    created_at timestamp with time zone not null default current_timestamp,
    foreign key(selected_vacancy_id, selected_building_id) references vacancies(id, building_id),
    check (selected_vacancy_id is null or selected_building_id is not null),
    check (selected_building_id is null or selected_address is null),
    check (preferred_building_id is null or preferred_address is null)
);
create index startup_plans_founder_idx on startup_plans(founder_id, created_at);

create table ai_jobs (
    id uuid primary key,
    account_id uuid references accounts(id),
    kind varchar(48) not null,
    scope_id uuid,
    input_data text not null,
    result_data text,
    status varchar(16) not null check (status in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    lease_id uuid,
    lease_expires_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    finished_at timestamp with time zone
);
create index ai_jobs_queue_idx on ai_jobs(status, created_at);

create table postings (
    id uuid primary key,
    owner_id uuid not null references accounts(id),
    startup_plan_id uuid references startup_plans(id),
    title varchar(180) not null,
    interest varchar(100) not null,
    work_description varchar(6000) not null,
    deliverable varchar(1500) not null,
    period_weeks int not null check (period_weeks between 1 and 52),
    academic_credit varchar(150),
    stipend_krw bigint not null check (stipend_krw >= 0),
    availability varchar(1000) not null,
    status varchar(16) not null check (status in ('DRAFT', 'PUBLISHED', 'MATCHED')),
    created_at timestamp with time zone not null default current_timestamp,
    published_at timestamp with time zone
);
create index postings_public_idx on postings(status, published_at);
create index postings_owner_idx on postings(owner_id, created_at);

create table applications (
    id uuid primary key,
    posting_id uuid not null references postings(id),
    student_id uuid not null references student_profiles(account_id),
    question_job_id uuid references ai_jobs(id),
    answers_json text not null,
    status varchar(20) not null check (status in ('SUBMITTED', 'SELECTED', 'NOT_SELECTED')),
    submitted_at timestamp with time zone not null default current_timestamp,
    unique(posting_id, student_id),
    unique(id, posting_id, student_id)
);
create index applications_student_idx on applications(student_id, submitted_at);

create table matches (
    id uuid primary key,
    posting_id uuid not null unique references postings(id),
    application_id uuid not null unique,
    student_id uuid not null references student_profiles(account_id),
    status varchar(20) not null check (status in ('MATCHED', 'IN_PROGRESS', 'COMPLETED')),
    handover_url varchar(1200),
    handover_notes varchar(2000),
    created_at timestamp with time zone not null default current_timestamp,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    foreign key(application_id, posting_id, student_id) references applications(id, posting_id, student_id)
);
