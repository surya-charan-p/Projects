%%% kv_app - simple object access service on top of Pastry.
%%%
%%% Associates a key (a 128-bit id, same space as nodeIds) with a string.
%%%   put(Key, Value) is routed to the node whose id is numerically closest to
%%%   Key, which stores it and replicates it on the next K-1 closest nodes of
%%%   its leaf set (as PAST does).
%%%   get(Key) is routed the same way; the root answers directly to the origin.
%%%
%%% The module implements the application side of the Pastry API (Section 2.3):
%%%   deliver/5  - called on the node responsible for Key
%%%   forward/4  - called on every intermediate node before forwarding
%%%   new_leafs/3 - called whenever the leaf set changes (re-replication)
%%% plus handle_direct/4, handle_control/3 and handle_timer/3 for the client
%%% side (issuing one request per second and collecting the replies).
-module(kv_app).

-export([init/2, deliver/5, forward/4, new_leafs/3,
         handle_direct/4, handle_control/3, handle_timer/3]).
-export([key_of/1, value_of/1]).

-record(kv, {self,              % our node descriptor
             store = #{},       % Key -> Value
             k = 3,             % replication factor
             timeout = infinity,% request timeout (ms)
             max_tries = 1,
             remaining = 0,     % requests still to issue
             interval = 1000,   % ms between requests
             seeds = 0,         % number of seeded objects that can be read
             seq = 0,
             out = #{},         % Ref -> {Phase, Msg, Key, Expected, Tries}
             started = false,
             done = false}).

init(Self, Opts) ->
    #kv{self = Self,
        k = maps:get(replicas, Opts, 3),
        timeout = maps:get(req_timeout, Opts, infinity),
        max_tries = maps:get(max_tries, Opts, 1)}.

key_of(I) -> pastry_util:hash_id({object, I}).

value_of(I) -> lists:flatten(io_lib:format("payload of object #~p", [I])).

%%% ------------------------------------------------------ Pastry upcalls

deliver({put, Key, Val, Origin, Ref}, Key, Hops, Ctx, St = #kv{store = S}) ->
    {SId, _, _} = St#kv.self,
    Reps = [{send, P, {replica, Key, Val}} || {_, P, _} <- replicas(Key, Ctx, St)],
    {St#kv{store = S#{Key => Val}},
     [{send, Origin, {put_ok, Ref, Hops, SId}} | Reps]};
deliver({get, Key, Origin, Ref}, Key, Hops, _Ctx, St = #kv{store = S}) ->
    {SId, _, _} = St#kv.self,
    Res = case maps:find(Key, S) of
              {ok, V} -> {found, V};
              error -> not_found
          end,
    {St, [{send, Origin, {get_reply, Ref, Hops, SId, Res}}]};
deliver(_Msg, _Key, _Hops, _Ctx, St) ->
    {St, []}.

forward(Msg, _Key, _NextId, _St) ->
    {ok, Msg}.

%% Leaf set changed: for every object we are now root of, push it to the
%% K-1 nodes that should hold replicas.
new_leafs(_Leaves, _Ctx, St = #kv{store = S}) when map_size(S) =:= 0 ->
    {St, []};
new_leafs(_Leaves, Ctx, St = #kv{self = {SId, _, _}, store = S}) ->
    Leaves = maps:get(leaves, Ctx),
    Acts = lists:append(
             [[{send, P, {replica, K, V}} || {_, P, _} <- replicas(K, Ctx, St)]
              || {K, V} <- maps:to_list(S), is_root(K, SId, Leaves)]),
    {St, Acts}.

%% The K-1 leaves (other than us) closest to Key.
replicas(_Key, _Ctx, #kv{k = 1}) ->
    [];
replicas(Key, Ctx, #kv{k = K}) ->
    Leaves = maps:get(leaves, Ctx),
    Sorted = lists:sort(fun({A, _, _}, {B, _, _}) -> pastry_util:closer(A, B, Key) end,
                        Leaves),
    lists:sublist(Sorted, K - 1).

is_root(Key, SId, Leaves) ->
    lists:all(fun({I, _, _}) -> pastry_util:closer(SId, I, Key) end, Leaves).

%%% -------------------------------------------------- direct messages

handle_direct({replica, K, V}, _From, _Ctx, St = #kv{store = S}) ->
    {St#kv{store = S#{K => V}}, []};
handle_direct({put_ok, Ref, Hops, Root}, _From, Ctx, St) ->
    complete(Ref, Hops, Root, ok, Ctx, St);
handle_direct({get_reply, Ref, Hops, Root, Res}, _From, Ctx, St = #kv{out = Out}) ->
    Status = case {maps:find(Ref, Out), Res} of
                 {{ok, {_, _, _, Exp, _}}, {found, Exp}} -> ok;
                 {_, {found, _}} -> wrong_value;
                 {_, not_found} -> not_found
             end,
    complete(Ref, Hops, Root, Status, Ctx, St);
handle_direct(_, _From, _Ctx, St) ->
    {St, []}.

complete(Ref, Hops, Root, Status, Ctx, St = #kv{out = Out}) ->
    case maps:take(Ref, Out) of
        error ->
            {St, []};                               % late duplicate reply
        {{Phase, _Msg, Key, _Exp, Tries}, Out2} ->
            Report = case Phase of
                         seed -> {out, maps:get(main, Ctx), {seed_done, Status}};
                         request -> {out, maps:get(collector, Ctx),
                                     {req, Hops, Status, Key, Root, Tries}}
                     end,
            maybe_done(St#kv{out = Out2}, [Report], Ctx)
    end.

%%% ------------------------------------------------ control and timers

handle_control({seed_put, I}, _Ctx, St) ->
    issue(seed, put, key_of(I), value_of(I), St);
handle_control({start, NReq, Interval, Seeds}, Ctx, St) ->
    St2 = St#kv{remaining = NReq, interval = Interval, seeds = Seeds, started = true},
    case NReq of
        0 -> maybe_done(St2, [], Ctx);
        _ -> {St2, [{timer, rand:uniform(Interval) - 1, tick}]}
    end;
handle_control(_, _Ctx, St) ->
    {St, []}.

handle_timer(tick, Ctx, St = #kv{remaining = R}) when R > 0 ->
    {St2, Acts} = new_request(St#kv{remaining = R - 1}),
    Next = case R - 1 of
               0 -> [];
               _ -> [{timer, St#kv.interval, tick}]
           end,
    maybe_done(St2, Acts ++ Next, Ctx);
handle_timer({req_timeout, Ref, Try}, Ctx, St = #kv{out = Out}) ->
    case maps:find(Ref, Out) of
        {ok, {Phase, Msg, Key, Exp, Try}} when Try < St#kv.max_tries ->
            %% retry the same request (same Ref, so a late reply still counts)
            St2 = St#kv{out = Out#{Ref => {Phase, Msg, Key, Exp, Try + 1}}},
            {St2, [{route, Msg, Key}, {timer, St#kv.timeout, {req_timeout, Ref, Try + 1}}]};
        {ok, {Phase, _Msg, Key, _Exp, Try}} ->
            St2 = St#kv{out = maps:remove(Ref, Out)},
            Report = case Phase of
                         seed -> {out, maps:get(main, Ctx), {seed_done, failed}};
                         request -> {out, maps:get(collector, Ctx),
                                     {req, none, failed, Key, none, Try}}
                     end,
            maybe_done(St2, [Report], Ctx);
        _ ->
            {St, []}
    end;
handle_timer(_, _Ctx, St) ->
    {St, []}.

%% Half of the requests read a seeded object (and check its value), half
%% store a fresh string under a new key.
new_request(St = #kv{seeds = Seeds, seq = Seq, self = {SId, _, _}}) ->
    case Seeds > 0 andalso rand:uniform(2) =:= 1 of
        true ->
            I = rand:uniform(Seeds),
            issue(request, get, key_of(I), value_of(I), St);
        false ->
            Key = pastry_util:hash_id({SId, Seq}),
            Val = lists:flatten(io_lib:format("hello from ~s, request ~p",
                                              [pastry_util:short(SId), Seq])),
            issue(request, put, Key, Val, St#kv{seq = Seq + 1})
    end.

issue(Phase, Op, Key, Val, St = #kv{out = Out}) ->
    Ref = make_ref(),
    Msg = case Op of
              put -> {put, Key, Val, self(), Ref};
              get -> {get, Key, self(), Ref}
          end,
    Exp = case Op of put -> undefined; get -> Val end,
    Timer = case St#kv.timeout of
                infinity -> [];
                T -> [{timer, T, {req_timeout, Ref, 1}}]
            end,
    {St#kv{out = Out#{Ref => {Phase, Msg, Key, Exp, 1}}}, [{route, Msg, Key} | Timer]}.

maybe_done(St = #kv{started = true, done = false, remaining = 0, out = Out}, Acts, Ctx)
  when map_size(Out) =:= 0 ->
    {St#kv{done = true}, Acts ++ [{out, maps:get(collector, Ctx), {node_done, self()}}]};
maybe_done(St, Acts, _Ctx) ->
    {St, Acts}.
