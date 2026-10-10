create table payments (
    id         uuid primary key,
    -- One payment per order, even if the same order arrives in two different events.
    order_id   uuid not null unique,
    amount     numeric(12, 2) not null,
    status     text not null,
    reason     text,
    created_at timestamptz not null
);

create index payments_newest_idx on payments (created_at desc, id desc);
