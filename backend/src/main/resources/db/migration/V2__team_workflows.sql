alter table accounts add column username varchar(30) unique;
alter table accounts add column active_role varchar(20)
    check (active_role in ('FOUNDER', 'OWNER', 'STUDENT', 'OPERATOR'));
update accounts set active_role = role;

create table account_roles (
    account_id uuid not null references accounts(id),
    role varchar(20) not null check (role in ('FOUNDER', 'OWNER', 'STUDENT', 'OPERATOR')),
    primary key (account_id, role)
);
insert into account_roles (account_id, role) select id, role from accounts where role is not null;

alter table building_opinions add column category varchar(100) not null default '기타';
alter table building_opinions add column vacancy_id uuid;
alter table building_opinions add foreign key (vacancy_id, building_id) references vacancies(id, building_id);
create index building_opinions_vacancy_idx on building_opinions(vacancy_id);

create table building_opinion_likes (
    opinion_id uuid not null references building_opinions(id),
    account_id uuid not null references accounts(id),
    created_at timestamp with time zone not null default current_timestamp,
    primary key (opinion_id, account_id)
);

alter table postings add column student_limit int not null default 4 check (student_limit between 1 and 4);
alter table postings add column advisor_name varchar(120);
alter table postings add column review_status varchar(24) not null default 'NOT_SUBMITTED'
    check (review_status in ('NOT_SUBMITTED', 'PENDING', 'SELECTED', 'NEXT_OPPORTUNITY'));
alter table postings add column review_building_id uuid references buildings(id);
alter table postings add column review_vacancy_id uuid;
alter table postings add foreign key (review_vacancy_id, review_building_id) references vacancies(id, building_id);
alter table postings add column review_requested_at timestamp with time zone;
alter table postings add column reviewed_at timestamp with time zone;
alter table postings add column reviewer_id uuid references accounts(id);
alter table postings add column readiness_score int check (readiness_score between 0 and 100);
alter table postings add column task_fit_score int check (task_fit_score between 0 and 100);
alter table postings add column review_opinion_count bigint;
alter table postings add column review_like_count bigint;
alter table postings add column review_notes varchar(1500);
update postings set review_status = 'SELECTED' where status in ('PUBLISHED', 'MATCHED');
create index postings_review_idx on postings(review_status, review_requested_at);

create table posting_team_matches (
    id uuid primary key,
    posting_id uuid not null references postings(id),
    application_id uuid not null unique,
    student_id uuid not null references student_profiles(account_id),
    status varchar(20) not null check (status in ('MATCHED', 'IN_PROGRESS', 'COMPLETED')),
    handover_url varchar(1200),
    handover_notes varchar(2000),
    created_at timestamp with time zone not null default current_timestamp,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    unique (posting_id, student_id),
    foreign key (application_id, posting_id, student_id) references applications(id, posting_id, student_id)
);
-- Preserve the original match table while moving the active workflow to the team table.
insert into posting_team_matches(id, posting_id, application_id, student_id, status, handover_url,
    handover_notes, created_at, started_at, completed_at)
select id, posting_id, application_id, student_id, status, handover_url, handover_notes,
    created_at, started_at, completed_at from matches;
