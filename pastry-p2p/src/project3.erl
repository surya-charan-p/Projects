%%% project3 - Pastry overlay + key/value service driver.
%%%
%%%   project3 numNodes numRequests [options]
%%%
%%% Builds a Pastry network of numNodes peers (one actor each) by joining
%%% them one after another through the Pastry join protocol, seeds the
%%% key/value store, then lets every peer issue numRequests requests (one per
%%% second) and prints the average number of overlay hops per request.
%%%
%%% Options (all optional; the failure options are the bonus part):
%%%   --interval MS      ms between two requests of a peer (default 1000)
%%%   --verify           check every leaf set against the true ring neighbours
%%%   --seed S           random seed for reproducible runs
%%%   --kill P           crash P% of the peers during the request phase
%%%   --link-temp P      cut a link of P% of the peers temporarily
%%%   --link-perm P      cut a link of P% of the peers permanently
%%%   --link-dur MS      duration of a temporary link failure (default 3000)
%%%   --replicas K       copies kept of every object (default 3)
%%%   --no-ft            inject failures but disable fault tolerance
%%%   --csv FILE         append one line of results to FILE
-module(project3).

-export([start/0, main/1, injector/4]).

%% Entry point used by the ./project3 wrapper (arguments after -extra).
start() ->
    main(init:get_plain_arguments()).

main(Args) ->
    Code = try
               run(parse(Args))
           catch
               throw:{usage, Msg} ->
                   io:format("~s~n~n~s", [Msg, usage()]),
                   1
           end,
    erlang:halt(Code).

usage() ->
    "usage: project3 numNodes numRequests [--interval MS] [--verify] [--seed S]\n"
    "                [--kill P] [--link-temp P] [--link-perm P] [--link-dur MS]\n"
    "                [--replicas K] [--no-ft] [--csv FILE]\n".

%%% --------------------------------------------------------------- options

parse([N, R | Rest]) ->
    O = #{nodes => int(N), requests => int(R), interval => 1000, verify => false,
          seed => undefined, kill => 0.0, link_temp => 0.0, link_perm => 0.0,
          link_dur => 3000, replicas => 3, no_ft => false, csv => undefined},
    O2 = opts(Rest, O),
    maps:get(nodes, O2) >= 1 orelse throw({usage, "numNodes must be >= 1"}),
    maps:get(requests, O2) >= 0 orelse throw({usage, "numRequests must be >= 0"}),
    O2;
parse(_) ->
    throw({usage, "expected: numNodes numRequests"}).

opts([], O) -> O;
opts(["--interval", V | T], O) -> opts(T, O#{interval => int(V)});
opts(["--verify" | T], O) -> opts(T, O#{verify => true});
opts(["--seed", V | T], O) -> opts(T, O#{seed => int(V)});
opts(["--kill", V | T], O) -> opts(T, O#{kill => num(V)});
opts(["--link-temp", V | T], O) -> opts(T, O#{link_temp => num(V)});
opts(["--link-perm", V | T], O) -> opts(T, O#{link_perm => num(V)});
opts(["--link-dur", V | T], O) -> opts(T, O#{link_dur => int(V)});
opts(["--replicas", V | T], O) -> opts(T, O#{replicas => max(1, int(V))});
opts(["--no-ft" | T], O) -> opts(T, O#{no_ft => true});
opts(["--csv", V | T], O) -> opts(T, O#{csv => V});
opts([X | _], _) -> throw({usage, "unknown option " ++ X}).

int(S) ->
    try list_to_integer(S) catch _:_ -> throw({usage, "not an integer: " ++ S}) end.

num(S) ->
    try list_to_float(S) catch _:_ -> float(int(S)) end.

%%% ------------------------------------------------------------------ run

run(O = #{nodes := N, requests := R, interval := Iv}) ->
    case maps:get(seed, O) of
        undefined -> ok;
        Seed -> rand:seed(exsss, {Seed, Seed * 7 + 1, Seed * 13 + 2})
    end,
    Failures = maps:get(kill, O) > 0 orelse maps:get(link_temp, O) > 0
        orelse maps:get(link_perm, O) > 0,
    FT = Failures andalso not maps:get(no_ft, O),
    AppOpts = #{replicas => maps:get(replicas, O),
                req_timeout => case Failures of true -> 3000; false -> infinity end,
                max_tries => case FT of true -> 3; false -> 1 end},
    io:format("Pastry: numNodes=~p numRequests=~p  (b=4, |L|=16, |M|=16, "
              "replicas=~p, fault-tolerance=~s)~n",
              [N, R, maps:get(replicas, O), onoff(FT)]),
    Collector = pastry_collector:start(self()),
    NodeOpts = #{main => self(), collector => Collector, ft => FT},

    %% ---- 1. join phase
    T0 = erlang:monotonic_time(millisecond),
    Salt = rand:uniform(1 bsl 30),
    Nodes = join_all(N, Salt, AppOpts, NodeOpts),
    T1 = erlang:monotonic_time(millisecond),
    io:format("Join phase: ~p nodes joined in ~.2f s~n", [N, (T1 - T0) / 1000]),

    Verify = case maps:get(verify, O) of
                 true -> verify(Nodes);
                 false -> skipped
             end,

    %% ---- 2. seed the object store
    Seeds = max(16, min(N, 10000)),
    seed_store(Seeds, Nodes),

    %% ---- 3. request phase (and failure injection)
    Collector ! {expect, N, [Id || {Id, _, _} <- Nodes]},
    [begin
         P ! {ctl, start_ft},
         P ! {ctl, {app, {start, R, Iv, Seeds}}}
     end || {_, P, _} <- Nodes],
    Failures andalso
        spawn(?MODULE, injector, [O, list_to_tuple(Nodes), Collector, R * Iv]),
    io:format("Request phase: every peer sends ~p requests, one every ~p ms...~n", [R, Iv]),
    Stats = receive
                {collector_done, St} -> St
            after R * Iv + 120000 ->
                    Collector ! {get_stats, self()},
                    receive {stats, St} -> St#{timeout => true} end
            end,
    T2 = erlang:monotonic_time(millisecond),
    report(O, Stats, Verify, Failures, FT, (T1 - T0) / 1000, (T2 - T1) / 1000),
    0.

onoff(true) -> "on";
onoff(false) -> "off".

%% Nodes join one at a time.  Each new node X is given a node A that is close
%% to it in the proximity space (nearest of a random sample of joined nodes)
%% and runs the Pastry join protocol through A.
join_all(N, Salt, AppOpts, NodeOpts) ->
    Step = max(1, N div 10),
    join_all(1, N, Salt, AppOpts, NodeOpts, array:new(), Step).

join_all(I, N, _Salt, _AppOpts, _NodeOpts, Arr, _Step) when I > N ->
    array:to_list(Arr);
join_all(I, N, Salt, AppOpts, NodeOpts, Arr, Step) ->
    Id = pastry_util:hash_id({node, I, Salt}),
    Coord = {rand:uniform() * 1000, rand:uniform() * 1000},
    {Id, Pid} = pastry_node:start(Id, Coord, kv_app, AppOpts, NodeOpts),
    A = case I of
            1 -> none;
            _ -> nearest(Coord, sample(Arr, I - 1, 32))
        end,
    Pid ! {ctl, {join, A}},
    receive
        {joined, Pid} -> ok
    after 60000 ->
            io:format("node ~p did not finish joining~n", [I]),
            erlang:halt(2)
    end,
    (N >= 10000 andalso I rem Step =:= 0) andalso
        io:format("  ... ~p/~p joined~n", [I, N]),
    join_all(I + 1, N, Salt, AppOpts, NodeOpts, array:set(I - 1, {Id, Pid, Coord}, Arr), Step).

sample(Arr, Size, K) when Size =< K ->
    array:to_list(Arr);
sample(Arr, Size, K) ->
    [array:get(rand:uniform(Size) - 1, Arr) || _ <- lists:seq(1, K)].

nearest(C, [H | T]) ->
    lists:foldl(fun(D = {_, _, DC}, B = {_, _, BC}) ->
                        case pastry_util:prox(C, DC) < pastry_util:prox(C, BC) of
                            true -> D;
                            false -> B
                        end
                end, H, T).

%% Insert Seeds objects (key_of(I) -> value_of(I)) through random peers.
seed_store(Seeds, Nodes) ->
    T = list_to_tuple(Nodes),
    [begin
         {_, P, _} = element(rand:uniform(tuple_size(T)), T),
         P ! {ctl, {app, {seed_put, I}}}
     end || I <- lists:seq(1, Seeds)],
    Fails = lists:foldl(fun(_, F) ->
                                receive
                                    {seed_done, ok} -> F;
                                    {seed_done, _} -> F + 1
                                after 60000 -> F + 1
                                end
                        end, 0, lists:seq(1, Seeds)),
    io:format("Seeded ~p objects into the store (~p failed)~n", [Seeds, Fails]).

%% Compare every node's leaf set with the true ring neighbours.
verify(Nodes) ->
    Sorted = list_to_tuple(lists:sort([Id || {Id, _, _} <- Nodes])),
    N = tuple_size(Sorted),
    Index = maps:from_list(lists:zip(tuple_to_list(Sorted), lists:seq(1, N))),
    [P ! {ctl, {get_state, self()}} || {_, P, _} <- Nodes],
    Good = lists:foldl(
             fun(_, G) ->
                     receive
                         {node_state, _, Id, Leaves, _RtSize, _Nbr} ->
                             I = maps:get(Id, Index),
                             Expect = true_leaves(I, N, Sorted),
                             case lists:sort(Leaves) =:= Expect of
                                 true -> G + 1;
                                 false -> G
                             end
                     after 30000 -> G
                     end
             end, 0, Nodes),
    io:format("Verify: ~p/~p leaf sets match the true ring neighbours~n", [Good, N]),
    {Good, N}.

true_leaves(I, N, Sorted) when N - 1 =< 16 ->
    lists:sort(tuple_to_list(Sorted) -- [element(I, Sorted)]);
true_leaves(I, N, Sorted) ->
    Idx = [((I - 1 + D + N) rem N) + 1 || D <- lists:seq(-8, 8), D =/= 0],
    lists:sort([element(J, Sorted) || J <- Idx]).

%%% ------------------------------------------------------------- failures

%% Failure injector actor: schedules crashes and link failures at random
%% times during the first 60% of the request phase.
injector(O, Nodes, Collector, Duration) ->
    N = tuple_size(Nodes),
    Count = fun(Key) -> round(N * maps:get(Key, O) / 100) end,
    Events = [kill || _ <- lists:seq(1, min(Count(kill), N - 1))] ++
        [{link, temp} || _ <- lists:seq(1, Count(link_temp))] ++
        [{link, perm} || _ <- lists:seq(1, Count(link_perm))],
    Lo = Duration div 10,
    Span = max(1, (Duration * 6) div 10 - Lo),
    Timed = lists:keysort(1, [{Lo + rand:uniform(Span), E} || E <- Events]),
    inject(Timed, 0, Nodes, #{}, O, Collector).

inject([], _Now, _Nodes, _Killed, _O, _C) ->
    ok;
inject([{T, E} | Rest], Now, Nodes, Killed, O, C) ->
    timer:sleep(T - Now),
    Killed2 = case E of
                  kill ->
                      {Id, Pid, _} = pick_alive(Nodes, Killed),
                      exit(Pid, kill),
                      C ! {killed, Pid, Id},
                      Killed#{Pid => true};
                  {link, Mode} ->
                      {_, Pid, _} = pick_alive(Nodes, Killed),
                      Pid ! {ctl, {fail_link, Mode, maps:get(link_dur, O)}},
                      Killed
              end,
    inject(Rest, T, Nodes, Killed2, O, C).

pick_alive(Nodes, Killed) ->
    D = {_, Pid, _} = element(rand:uniform(tuple_size(Nodes)), Nodes),
    case maps:is_key(Pid, Killed) of
        true -> pick_alive(Nodes, Killed);
        false -> D
    end.

%%% --------------------------------------------------------------- output

report(O, S, Verify, Failures, FT, JoinSecs, ReqSecs) ->
    #{requests := Reqs, delivered := Del, avg_hops := Avg, status := St,
      correct := Cor, misrouted := Mis} = S,
    N = maps:get(nodes, O),
    io:format("~n==================== results ====================~n"),
    io:format("Requests completed     : ~p (delivered ~p)~n", [Reqs, Del]),
    io:format("Request outcomes       : ~p~n", [St]),
    io:format("Routed to closest node : ~p / ~p~n", [Cor, Cor + Mis]),
    io:format("Hop histogram          : ~p~n", [lists:sort(maps:to_list(maps:get(hist, S)))]),
    io:format("log16(numNodes)        : ~.3f~n", [math:log(N) / math:log(16)]),
    Failures andalso
        io:format("Failures injected      : ~p crashed, links ~p; ~p failures detected, "
                  "~p requests retried, ~p TTL drops~n",
                  [maps:get(killed, S), maps:get(links, S), maps:get(detected, S),
                   maps:get(retried, S), maps:get(ttl_drops, S)]),
    maps:get(timeout, S, false) andalso
        io:format("WARNING: not every peer finished before the timeout~n"),
    io:format("Request phase time     : ~.2f s~n", [ReqSecs]),
    io:format("~nAverage number of hops: ~.4f~n", [Avg]),
    case maps:get(csv, O) of
        undefined -> ok;
        File ->
            Ok = maps:get(ok, St, 0),
            Line = io_lib:format("~p,~p,~p,~p,~p,~p,~p,~s,~.4f,~p,~p,~p,~p,~p,~p,~p,~.2f,~p~n",
                                 [N, maps:get(requests, O), maps:get(kill, O),
                                  maps:get(link_temp, O), maps:get(link_perm, O),
                                  maps:get(link_dur, O), maps:get(replicas, O), onoff(FT),
                                  Avg, Reqs, Ok, maps:get(not_found, St, 0),
                                  maps:get(failed, St, 0), maps:get(wrong_value, St, 0),
                                  Cor, Mis, JoinSecs,
                                  case Verify of {G, _} -> G; skipped -> -1 end]),
            file:write_file(File, Line, [append])
    end.
