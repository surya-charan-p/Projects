%%% pastry_collector - statistics actor.
%%%
%%% Receives one report per finished request from the origin peers, checks
%%% that the node which answered is really the live node numerically closest
%%% to the key, and tells the main process when every peer is finished (or
%%% has been killed by the failure injector).
-module(pastry_collector).

-export([start/1, init/1]).

-record(c, {main,
            n = 0,             % peers expected to report node_done
            ids = {},          % sorted tuple of all node ids
            done = #{},        % Pid -> true  (finished or killed)
            dead = #{},        % Id -> true   (killed nodes)
            reqs = 0, hop_sum = 0, hist = #{},
            status = #{},      % ok | not_found | wrong_value | failed -> count
            correct = 0, misrouted = 0, retried = 0,
            killed = 0, links = #{}, detected = 0, ttl = 0,
            finished = false}).

start(Main) -> spawn(?MODULE, init, [Main]).

init(Main) -> loop(#c{main = Main}).

loop(C) ->
    receive
        {expect, N, Ids} ->
            loop(check(C#c{n = N, ids = list_to_tuple(lists:sort(Ids))}));
        {req, Hops, Status, Key, Root, Tries} ->
            C2 = C#c{reqs = C#c.reqs + 1,
                     status = incr(Status, C#c.status),
                     retried = C#c.retried + min(1, Tries - 1)},
            C3 = case Hops of
                     none -> C2;
                     _ ->
                         Ok = Root =:= closest_live(Key, C2),
                         C2#c{hop_sum = C2#c.hop_sum + Hops,
                              hist = incr(Hops, C2#c.hist),
                              correct = C2#c.correct + b2i(Ok),
                              misrouted = C2#c.misrouted + b2i(not Ok)}
                 end,
            loop(C3);
        {node_done, Pid} ->
            loop(check(C#c{done = (C#c.done)#{Pid => true}}));
        {killed, Pid, Id} ->
            loop(check(C#c{done = (C#c.done)#{Pid => true},
                           dead = (C#c.dead)#{Id => true},
                           killed = C#c.killed + 1}));
        {link_failed, Mode} ->
            loop(C#c{links = incr(Mode, C#c.links)});
        {detected, _} ->
            loop(C#c{detected = C#c.detected + 1});
        {ttl_drop, _} ->
            loop(C#c{ttl = C#c.ttl + 1});
        {get_stats, From} ->
            From ! {stats, stats(C)},
            loop(C)
    end.

check(C = #c{finished = false, n = N, done = D, main = Main}) when N > 0, map_size(D) >= N ->
    Main ! {collector_done, stats(C)},
    C#c{finished = true};
check(C) ->
    C.

stats(C) ->
    Delivered = C#c.reqs - maps:get(failed, C#c.status, 0),
    #{requests => C#c.reqs, delivered => Delivered,
      avg_hops => case Delivered of 0 -> 0.0; _ -> C#c.hop_sum / Delivered end,
      hist => C#c.hist, status => C#c.status,
      correct => C#c.correct, misrouted => C#c.misrouted, retried => C#c.retried,
      killed => C#c.killed, links => C#c.links, detected => C#c.detected,
      ttl_drops => C#c.ttl, finished => map_size(C#c.done)}.

%% Id of the live node numerically closest to Key (binary search on the ring).
closest_live(Key, #c{ids = T, dead = Dead}) ->
    N = tuple_size(T),
    I = lb(T, Key, 1, N + 1),
    Succ = walk(T, Dead, wrap(I, N), 1, N, N),
    Pred = walk(T, Dead, wrap(I - 1, N), -1, N, N),
    case pastry_util:closer(Pred, Succ, Key) of
        true -> Pred;
        false -> Succ
    end.

lb(T, K, Lo, Hi) when Lo < Hi ->
    Mid = (Lo + Hi) div 2,
    case element(Mid, T) < K of
        true -> lb(T, K, Mid + 1, Hi);
        false -> lb(T, K, Lo, Mid)
    end;
lb(_, _, Lo, _) -> Lo.

wrap(I, N) when I > N -> I - N;
wrap(I, N) when I < 1 -> I + N;
wrap(I, _) -> I.

walk(T, _Dead, I, _Step, _N, 0) -> element(I, T);
walk(T, Dead, I, Step, N, Left) ->
    Id = element(I, T),
    case maps:is_key(Id, Dead) of
        false -> Id;
        true -> walk(T, Dead, wrap(I + Step, N), Step, N, Left - 1)
    end.

incr(K, M) -> maps:update_with(K, fun(V) -> V + 1 end, 1, M).

b2i(true) -> 1;
b2i(false) -> 0.
