create table stores (
    id uuid primary key,
    owner_id uuid not null unique references accounts(id),
    name varchar(160) not null,
    category varchar(100) not null,
    road_address varchar(300),
    longitude decimal(11,8) not null check (longitude between -180 and 180),
    latitude decimal(10,8) not null check (latitude between -90 and 90),
    first_visit_benefit varchar(300),
    offer_details varchar(300),
    created_at timestamp with time zone not null default current_timestamp
);

create table store_menus (
    id uuid primary key,
    store_id uuid not null references stores(id),
    name varchar(120) not null,
    price_krw bigint check (price_krw >= 0),
    active boolean not null default true,
    unique (store_id, name),
    unique (id, store_id)
);

create table resident_request_answers (
    id uuid primary key,
    opinion_id uuid not null references building_opinions(id),
    store_id uuid not null references stores(id),
    menu_id uuid not null,
    message varchar(1000) not null,
    status varchar(16) not null check (status in ('PREPARING', 'SELLING', 'ENDED')),
    hidden boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (opinion_id, store_id),
    foreign key (menu_id, store_id) references store_menus(id, store_id)
);
create index resident_request_answers_store_idx on resident_request_answers(store_id);

create table resident_alert_preferences (
    account_id uuid primary key references accounts(id),
    enabled boolean not null
);

create table resident_alerts (
    id uuid primary key,
    account_id uuid not null references accounts(id),
    answer_id uuid not null references resident_request_answers(id),
    kind varchar(20) not null check (kind in ('ANSWER_POSTED', 'ANSWER_ENDED')),
    created_at timestamp with time zone not null default current_timestamp,
    read_at timestamp with time zone,
    unique (account_id, answer_id, kind)
);
create index resident_alerts_account_idx on resident_alerts(account_id, created_at);

create table answer_visits (
    answer_id uuid not null references resident_request_answers(id),
    account_id uuid not null references accounts(id),
    visited_at timestamp with time zone not null default current_timestamp,
    primary key (answer_id, account_id)
);
create index answer_visits_date_idx on answer_visits(visited_at, answer_id);

create table answer_reports (
    answer_id uuid not null references resident_request_answers(id),
    account_id uuid not null references accounts(id),
    reason varchar(500) not null,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (answer_id, account_id)
);

create table answer_reviews (
    answer_id uuid not null,
    account_id uuid not null,
    rating int not null check (rating between 1 and 5),
    comment_text varchar(1500) not null,
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (answer_id, account_id),
    foreign key (answer_id, account_id) references answer_visits(answer_id, account_id)
);

create table store_menu_days (
    menu_id uuid not null references store_menus(id),
    sold_on date not null,
    sold_quantity int not null check (sold_quantity >= 0),
    sales_krw bigint check (sales_krw >= 0),
    sold_out boolean not null,
    primary key (menu_id, sold_on)
);

create table answer_visit_days (
    answer_id uuid not null references resident_request_answers(id),
    visited_on date not null,
    shown_visits int not null check (shown_visits >= 0),
    primary key (answer_id, visited_on)
);

create table store_ingredients (
    id uuid primary key,
    store_id uuid not null references stores(id),
    name varchar(120) not null,
    unit varchar(20) not null,
    on_hand decimal(14,3) not null check (on_hand >= 0),
    pack_quantity decimal(14,3) not null check (pack_quantity > 0),
    lead_days int not null check (lead_days between 0 and 30),
    updated_at timestamp with time zone not null default current_timestamp,
    unique (store_id, name),
    unique (id, store_id)
);

create table store_menu_ingredients (
    menu_id uuid not null,
    ingredient_id uuid not null,
    store_id uuid not null,
    quantity_per_sale decimal(14,3) not null check (quantity_per_sale > 0),
    primary key (menu_id, ingredient_id),
    foreign key (menu_id, store_id) references store_menus(id, store_id),
    foreign key (ingredient_id, store_id) references store_ingredients(id, store_id)
);

create table store_supply_orders (
    id uuid primary key,
    ingredient_id uuid not null,
    store_id uuid not null,
    quantity decimal(14,3) not null check (quantity > 0),
    arrives_on date not null,
    status varchar(12) not null check (status in ('ORDERED', 'RECEIVED', 'CANCELLED')),
    created_at timestamp with time zone not null default current_timestamp,
    foreign key (ingredient_id, store_id) references store_ingredients(id, store_id)
);
create index store_supply_orders_store_idx on store_supply_orders(store_id, status, arrives_on);

create table store_sharing (
    store_id uuid primary key references stores(id),
    share_status boolean not null,
    share_sales boolean not null,
    share_visits boolean not null
);

create table store_follow_ups (
    store_id uuid not null references stores(id),
    month_start date not null,
    business_status varchar(20) not null check (business_status in ('OPERATING', 'PAUSED', 'CLOSED')),
    sales_band varchar(80),
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (store_id, month_start)
);

alter table vacancies add column registered_at timestamp with time zone;
alter table vacancies alter column registered_at set default current_timestamp;

create table shop_quarters (
    quarter varchar(7) primary key,
    source varchar(250) not null,
    coverage varchar(100) not null,
    imported_at timestamp with time zone not null default current_timestamp
);

create table quarterly_shops (
    quarter varchar(7) not null references shop_quarters(quarter),
    shop_key varchar(100) not null,
    name varchar(200) not null,
    category varchar(100) not null,
    building_key varchar(100),
    longitude decimal(11,8) not null check (longitude between -180 and 180),
    latitude decimal(10,8) not null check (latitude between -90 and 90),
    primary key (quarter, shop_key)
);
create index quarterly_shops_location_idx on quarterly_shops(quarter, latitude, longitude);
