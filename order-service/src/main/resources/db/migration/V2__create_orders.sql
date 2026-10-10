create table orders (
    id                  uuid primary key,
    customer_id         text not null,
    customer_name       text not null,
    customer_email      text not null,
    status              text not null,
    total_amount        numeric(12, 2),
    cancellation_reason text,
    cancellation_detail text,
    payment_approved    boolean not null,
    item_count          integer not null,
    created_at          timestamptz not null,
    updated_at          timestamptz not null
);

create index orders_newest_idx on orders (created_at desc, id desc);
create index orders_customer_idx on orders (customer_id, created_at desc);
create index orders_status_idx on orders (status, created_at desc);

create table order_items (
    order_id     uuid not null references orders (id),
    seq          integer not null,
    product_id   uuid not null,
    product_name text,
    quantity     integer not null,
    unit_price   numeric(12, 2),
    primary key (order_id, seq)
);

create table order_history (
    order_id    uuid not null references orders (id),
    seq         integer not null,
    status      text not null,
    occurred_at timestamptz not null,
    detail      text not null,
    primary key (order_id, seq)
);
