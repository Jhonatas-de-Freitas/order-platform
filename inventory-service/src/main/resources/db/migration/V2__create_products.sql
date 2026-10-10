create table products (
    id                 uuid primary key,
    name               text not null,
    description        text,
    price              numeric(12, 2) not null check (price > 0),
    -- Last line of defense: whatever the code does, the balance cannot go below zero.
    available_quantity integer not null check (available_quantity >= 0)
);
