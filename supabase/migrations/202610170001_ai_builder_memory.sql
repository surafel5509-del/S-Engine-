-- S Engine AI Builder private history and per-user request quotas.
-- OpenRouter secrets are not stored in these client-readable tables.

create table if not exists public.ai_memory_records (
    client_id uuid primary key,
    owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
    project_key text not null check (char_length(project_key) between 1 and 100),
    kind text not null check (kind in ('message', 'decision', 'build', 'repair')),
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists ai_memory_records_owner_project_created_idx
    on public.ai_memory_records (owner_id, project_key, created_at desc);
create index if not exists ai_memory_records_owner_kind_created_idx
    on public.ai_memory_records (owner_id, kind, created_at desc);

alter table public.ai_memory_records enable row level security;

revoke all on public.ai_memory_records from anon;
grant select, insert, update, delete on public.ai_memory_records to authenticated;

 drop policy if exists "Users read their own AI memory" on public.ai_memory_records;
create policy "Users read their own AI memory"
    on public.ai_memory_records for select to authenticated
    using (owner_id = auth.uid());

drop policy if exists "Users create their own AI memory" on public.ai_memory_records;
create policy "Users create their own AI memory"
    on public.ai_memory_records for insert to authenticated
    with check (owner_id = auth.uid());

drop policy if exists "Users update their own AI memory" on public.ai_memory_records;
create policy "Users update their own AI memory"
    on public.ai_memory_records for update to authenticated
    using (owner_id = auth.uid())
    with check (owner_id = auth.uid());

drop policy if exists "Users delete their own AI memory" on public.ai_memory_records;
create policy "Users delete their own AI memory"
    on public.ai_memory_records for delete to authenticated
    using (owner_id = auth.uid());

create or replace function public.set_ai_memory_updated_at()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists ai_memory_records_set_updated_at on public.ai_memory_records;
create trigger ai_memory_records_set_updated_at
    before update on public.ai_memory_records
    for each row execute function public.set_ai_memory_updated_at();

create table if not exists public.ai_usage_windows (
    owner_id uuid not null references auth.users(id) on delete cascade,
    window_start timestamptz not null,
    request_count integer not null check (request_count between 1 and 30),
    primary key (owner_id, window_start)
);

alter table public.ai_usage_windows enable row level security;
revoke all on public.ai_usage_windows from anon, authenticated;
grant all on public.ai_usage_windows to service_role;

-- Atomic hourly request limit. The Edge Function invokes this with the service-role key only
-- after it has verified the caller's JWT against Supabase Auth.
create or replace function public.claim_ai_builder_request(p_owner_id uuid, p_limit integer default 30)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    claimed_count integer;
    current_window timestamptz := date_trunc('hour', now());
begin
    if p_owner_id is null or p_limit < 1 or p_limit > 30 then
        return false;
    end if;

    insert into public.ai_usage_windows(owner_id, window_start, request_count)
    values (p_owner_id, current_window, 1)
    on conflict (owner_id, window_start) do update
       set request_count = public.ai_usage_windows.request_count + 1
     where public.ai_usage_windows.request_count < p_limit
    returning request_count into claimed_count;

    return found;
end;
$$;

revoke all on function public.claim_ai_builder_request(uuid, integer) from public, anon, authenticated;
grant execute on function public.claim_ai_builder_request(uuid, integer) to service_role;

comment on table public.ai_memory_records is
    'Private per-user S Engine AI conversation, project-decision, and build memory. Client IDs make sync idempotent.';
comment on table public.ai_usage_windows is
    'Server-side hourly rate-limit counters for AI Edge Function requests.';
