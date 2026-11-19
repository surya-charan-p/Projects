%%% pastry_node - one Pastry peer, implemented as a single Erlang actor.
%%%
%%% Implements the Pastry API of Rowstron & Druschel (Section 2.3):
%%%   pastryInit(Credentials, Application) -> start/5 (returns the nodeId)
%%%   route(Msg, Key)                      -> route/3
%%% and calls back into the application module with
%%%   deliver(Msg, Key)  forward(Msg, Key, NextId)  newLeafs(LeafSet).
%%%
%%% Node state (Section 2.1, b = 4):
%%%   routing table   R : map {Row, Col} -> Desc   (32 rows x 16 columns)
%%%   leaf set        L : 8 numerically smaller + 8 numerically larger nodes
%%%   neighborhood    M : 16 nodes closest by the proximity metric
%%% where Desc = {NodeId, Pid, Coord}.
%%%
%%% Every message between peers travels as {pastry, FromPid, Body} and goes
%%% through send/3, which models the network link (it can be cut by the
%%% failure injector).  When fault tolerance (ft) is on, every overlay hop is
%%% acknowledged, leaf-set members are probed with keep-alives, and failed
%%% entries are repaired as described in Section 2.4 of the paper.
-module(pastry_node).

-export([start/5, route/3, init/5]).

-define(L, 16).                 % |L|
-define(HALF, 8).               % |L|/2
-define(M, 16).                 % |M|
-define(HOP_TIMEOUT, 400).      % ms to wait for a per-hop ack
-define(KEEPALIVE, 1000).       % ms between leaf-set keep-alive rounds
-define(MAX_MISSED, 2).         % unanswered pings before a leaf is declared dead
-define(SUSPECT_TTL, 10000).    % ms a failed peer is kept out of the tables
-define(MAX_HOPS, 64).          % safety TTL for routed messages

-record(st, {self,              % {Id, Pid, Coord}
             left = [],         % smaller half of the leaf set, closest first
             right = [],        % larger half of the leaf set, closest first
             leaf_all = true,   % true while the node knows <= |L| other nodes
             rt = #{},          % routing table
             nbr = [],          % neighborhood set, closest first
             app, app_st,       % application callback module and its state
             main, collector,
             ft = false,        % fault tolerance enabled
             down = #{},        % Pid -> Until | infinity : links that are cut
             pending = #{},     % Ref -> {Next, Msg, Key, Hops}  (un-acked hops)
             pings = #{},       % Pid -> unanswered keep-alives
             suspects = #{},    % Pid -> expiry time
             join}).            % join bookkeeping while joining

%%% ------------------------------------------------------------------ API

%% pastryInit: create a node with the given credentials (id + coordinates)
%% bound to application module App.  Returns {NodeId, Pid}.
start(Id, Coord, App, AppOpts, Opts) ->
    Pid = spawn(?MODULE, init, [Id, Coord, App, AppOpts, Opts]),
    {Id, Pid}.

%% route(Msg, Key): ask Node to route application message Msg towards Key.
route(Node, Msg, Key) ->
    Node ! {ctl, {route, Msg, Key}},
    ok.

init(Id, Coord, App, AppOpts, Opts) ->
    Self = {Id, self(), Coord},
    S = #st{self = Self,
            app = App,
            app_st = App:init(Self, AppOpts),
            main = maps:get(main, Opts),
            collector = maps:get(collector, Opts),
            ft = maps:get(ft, Opts, false)},
    loop(S).

loop(S) ->
    receive
        Msg -> loop(handle_msg(Msg, S))
    end.

%%% ------------------------------------------------------------ dispatch

handle_msg({pastry, From, Body}, S) ->
    case is_down(From, S) of
        true -> S;                                  % link is cut: message lost
        false -> handle(Body, From, S)
    end;
handle_msg({ctl, C}, S) ->
    control(C, S);
handle_msg({hop_timeout, Ref}, S = #st{pending = P}) ->
    case maps:take(Ref, P) of
        error -> S;
        {{Next, Msg, Key, Hops}, P2} ->
            S2 = node_failed(Next, S#st{pending = P2}),
            do_route(Msg, Key, Hops, S2)
    end;
handle_msg({app_timer, T}, S) ->
    app_apply(handle_timer, [T], S);
handle_msg(keepalive, S) ->
    keepalive(S);
handle_msg(_, S) ->
    S.

%%% ------------------------------------------------- control (out of band)

control({join, none}, S = #st{main = Main}) ->
    Main ! {joined, self()},
    S;
control({join, {_, APid, _}}, S = #st{self = Self}) ->
    send(APid, {join_req, Self}, S),
    S#st{join = #{states => 0, final => undefined, cands => []}};
control({route, Msg, Key}, S) ->
    do_route({app, Msg}, Key, 0, S);
control({app, AppMsg}, S) ->
    app_apply(handle_control, [AppMsg], S);
control(start_ft, S = #st{ft = true}) ->
    erlang:send_after(rand:uniform(?KEEPALIVE), self(), keepalive),
    S;
control(start_ft, S) ->
    S;
control({fail_link, Mode, Dur}, S = #st{collector = C}) ->
    case [P || {_, P, _} <- known(S)] of
        [] -> S;
        Peers ->
            Peer = lists:nth(rand:uniform(length(Peers)), Peers),
            Until = case Mode of
                        perm -> infinity;
                        temp -> now_ms() + Dur
                    end,
            Peer ! {ctl, {link_down, self(), Until}},
            C ! {link_failed, Mode},
            S#st{down = (S#st.down)#{Peer => Until}}
    end;
control({link_down, Peer, Until}, S) ->
    S#st{down = (S#st.down)#{Peer => Until}};
control({get_state, From}, S = #st{self = {Id, _, _}}) ->
    From ! {node_state, self(), Id,
            [I || {I, _, _} <- S#st.left ++ S#st.right],
            maps:size(S#st.rt),
            [I || {I, _, _} <- S#st.nbr]},
    S;
control(_, S) ->
    S.

%%% ----------------------------------------------- peer-to-peer messages

handle({route, Msg, Key, Hops, Ref}, From, S) ->
    Ref =/= none andalso send(From, {hop_ack, Ref}, S),
    case Hops > ?MAX_HOPS of
        true ->
            S#st.collector ! {ttl_drop, Key},
            S;
        false ->
            do_route(Msg, Key, Hops, S)
    end;
handle({hop_ack, Ref}, _From, S = #st{pending = P}) ->
    S#st{pending = maps:remove(Ref, P)};

%% ---- join (Section 2.4)
handle({join_req, XDesc = {XId, _, _}}, _From, S) ->
    %% we are node A, the node "near" X that X contacted first
    do_route({join, XDesc}, XId, 0, S);
handle({join_state, Desc, Cands, Final, Hops}, _From, S = #st{join = J}) ->
    #{states := N, cands := Cs, final := F} = J,
    J2 = J#{states := N + 1,
            cands := [Desc | Cands] ++ Cs,
            final := case Final of true -> Hops; false -> F end},
    maybe_finish_join(S#st{join = J2});
handle({announce, XDesc}, From, S) ->
    S2 = learn([XDesc], S),
    send(From, announce_ack, S2),
    S2;
handle(announce_ack, _From, S = #st{join = J = #{acks := A}}) ->
    case A - 1 of
        0 ->
            S#st.main ! {joined, self()},
            S#st{join = undefined};
        A2 ->
            S#st{join = J#{acks := A2}}
    end;

%% ---- application
handle({app_direct, M}, From, S) ->
    app_apply(handle_direct, [M, From], S);

%% ---- failure detection and repair
handle({ping, Ref}, From, S) ->
    send(From, {pong, Ref}, S),
    S;
handle({pong, _Ref}, From, S = #st{pings = P}) ->
    S#st{pings = maps:remove(From, P)};
handle(leafset_req, From, S = #st{self = Self}) ->
    send(From, {leafset_resp, Self, S#st.left ++ S#st.right}, S),
    S;
handle({leafset_resp, Desc, Leaves}, _From, S) ->
    learn([Desc | Leaves], S);
handle({rt_req, Row, Col}, From, S = #st{rt = RT}) ->
    send(From, {rt_resp, maps:get({Row, Col}, RT, none)}, S),
    S;
handle({rt_resp, none}, _From, S) ->
    S;
handle({rt_resp, Desc}, _From, S) ->
    learn([Desc], S);
handle(_, _From, S) ->
    S.

maybe_finish_join(S = #st{join = #{states := N, final := F, cands := Cs}})
  when is_integer(F), N =:= F + 1 ->
    %% every node on the join path has reported: build our own state
    S2 = learn(Cs, S),
    Peers = lists:usort([P || {_, P, _} <- known(S2)]),
    [send(P, {announce, S2#st.self}, S2) || P <- Peers],
    case Peers of
        [] ->
            S2#st.main ! {joined, self()},
            S2#st{join = undefined};
        _ ->
            S2#st{join = #{acks => length(Peers)}}
    end;
maybe_finish_join(S) ->
    S.

%%% ------------------------------------------------------------- routing

%% Routing procedure of Table 1 in the paper.
do_route(Msg, Key, Hops, S = #st{self = Self}) ->
    case next_hop(Key, S) of
        self ->
            deliver(Msg, Key, Hops, S);
        Next = {NextId, _, _} ->
            case Msg of
                {join, {_, XPid, _}} ->
                    %% each node on the path sends its state to X
                    send(XPid, {join_state, Self, known(S), false, Hops}, S),
                    forward_to(Next, Msg, Key, Hops, S);
                {app, AM} ->
                    #st{app = App, app_st = AS} = S,
                    case App:forward(AM, Key, NextId, AS) of
                        {ok, AM2} -> forward_to(Next, {app, AM2}, Key, Hops, S);
                        drop -> S
                    end
            end
    end.

deliver({join, {_, XPid, _}}, _Key, Hops, S = #st{self = Self}) ->
    send(XPid, {join_state, Self, known(S), true, Hops}, S),
    S;
deliver({app, AM}, Key, Hops, S) ->
    app_apply(deliver, [AM, Key, Hops], S).

forward_to({_, NPid, _} = Next, Msg, Key, Hops, S = #st{ft = true, pending = P}) ->
    Ref = make_ref(),
    send(NPid, {route, Msg, Key, Hops + 1, Ref}, S),
    erlang:send_after(?HOP_TIMEOUT, self(), {hop_timeout, Ref}),
    S#st{pending = P#{Ref => {Next, Msg, Key, Hops}}};
forward_to({_, NPid, _}, Msg, Key, Hops, S) ->
    send(NPid, {route, Msg, Key, Hops + 1, none}, S),
    S.

next_hop(Key, S = #st{self = Self = {SId, _, _}}) ->
    Leaves = S#st.left ++ S#st.right,
    case in_leaf_range(Key, S) of
        true ->
            %% deliver to the numerically closest member of L (or ourselves)
            case closest(Key, [Self | Leaves]) of
                Self -> self;
                D -> D
            end;
        false ->
            Row = pastry_util:shl(Key, SId),
            case maps:find({Row, pastry_util:digit(Key, Row)}, S#st.rt) of
                {ok, D} ->
                    D;
                error ->
                    %% rare case: any known node with a prefix at least as long
                    %% that is numerically closer to the key than we are
                    Cands = [D || D = {Id, _, _} <- known(S),
                                  pastry_util:shl(Id, Key) >= Row,
                                  pastry_util:closer(Id, SId, Key)],
                    case Cands of
                        [] -> self;
                        _ -> closest(Key, Cands)
                    end
            end
    end.

in_leaf_range(_Key, #st{leaf_all = true}) ->
    true;
in_leaf_range(Key, #st{self = {SId, _, _}, left = Left, right = Right}) ->
    {FarR, _, _} = lists:last(Right),
    {FarL, _, _} = lists:last(Left),
    pastry_util:cw(SId, Key) =< pastry_util:cw(SId, FarR) orelse
        pastry_util:cw(Key, SId) =< pastry_util:cw(FarL, SId).

closest(Key, [H | T]) ->
    lists:foldl(fun(D = {Id, _, _}, Best = {BId, _, _}) ->
                        case pastry_util:closer(Id, BId, Key) of
                            true -> D;
                            false -> Best
                        end
                end, H, T).

%%% ------------------------------------------------------- state updates

%% All nodes appearing in our state tables.
known(#st{left = L, right = R, rt = RT, nbr = N}) ->
    L ++ R ++ maps:values(RT) ++ N.

%% Incorporate newly learned node descriptors into R, L and M.
learn(Descs0, S = #st{self = {SId, SPid, SC}}) ->
    Now = now_ms(),
    Sus = S#st.suspects,
    Descs = [D || D = {_, P, _} <- Descs0, P =/= SPid,
                  not is_suspect(P, Sus, Now)],
    case Descs of
        [] ->
            S;
        _ ->
            RT = lists:foldl(fun(D, Acc) -> rt_insert(D, SId, SC, Acc) end,
                             S#st.rt, Descs),
            Nbr = lists:sublist(
                    lists:keysort(1, [{pastry_util:prox(C, SC), D} ||
                                         D = {_, _, C} <- lists:ukeysort(2, S#st.nbr ++ Descs)]),
                    ?M),
            S2 = S#st{rt = RT, nbr = [D || {_, D} <- Nbr]},
            set_leaves(S#st.left ++ S#st.right ++ Descs, S2)
    end.

is_suspect(P, Sus, Now) ->
    case maps:find(P, Sus) of
        {ok, Until} -> Now < Until;
        error -> false
    end.

rt_insert({Id, Pid, C} = D, SId, SC, RT) ->
    Row = pastry_util:shl(Id, SId),
    K = {Row, pastry_util:digit(Id, Row)},
    case maps:find(K, RT) of
        error -> RT#{K => D};
        {ok, {_, Pid, _}} -> RT;
        {ok, {_, _, EC}} ->
            %% keep the entry that is closer in the proximity space
            case pastry_util:prox(C, SC) < pastry_util:prox(EC, SC) of
                true -> RT#{K => D};
                false -> RT
            end
    end.

%% Rebuild the leaf set from a candidate list; fire newLeafs on change.
set_leaves(Cands, S = #st{self = {SId, _, _}}) ->
    U = lists:ukeysort(2, Cands),
    {Left, Right, All} =
        case length(U) =< ?L of
            true ->
                {R0, L0} = lists:partition(
                             fun({I, _, _}) ->
                                     pastry_util:cw(SId, I) =< pastry_util:cw(I, SId)
                             end, U),
                {sort_ccw(SId, L0), sort_cw(SId, R0), true};
            false ->
                {lists:sublist(sort_ccw(SId, U), ?HALF),
                 lists:sublist(sort_cw(SId, U), ?HALF), false}
        end,
    Old = lists:sort([P || {_, P, _} <- S#st.left ++ S#st.right]),
    New = lists:sort([P || {_, P, _} <- Left ++ Right]),
    S2 = S#st{left = Left, right = Right, leaf_all = All},
    case Old =:= New of
        true -> S2;
        false -> app_apply(new_leafs, [Left ++ Right], S2)
    end.

sort_cw(SId, Ds) ->
    [D || {_, D} <- lists:keysort(1, [{pastry_util:cw(SId, I), D} || D = {I, _, _} <- Ds])].

sort_ccw(SId, Ds) ->
    [D || {_, D} <- lists:keysort(1, [{pastry_util:cw(I, SId), D} || D = {I, _, _} <- Ds])].

%% A peer stopped answering: drop it from every table and start repair.
node_failed({_, Pid, _} = Desc, S) ->
    case lists:keymember(Pid, 2, known(S)) of
        false ->
            S;
        true ->
            S#st.collector ! {detected, Pid},
            InLeaf = lists:keymember(Pid, 2, S#st.left ++ S#st.right),
            WasLeft = lists:keymember(Pid, 2, S#st.left),
            Slots = [K || {K, {_, P, _}} <- maps:to_list(S#st.rt), P =:= Pid],
            S1 = S#st{rt = maps:without(Slots, S#st.rt),
                      nbr = lists:keydelete(Pid, 2, S#st.nbr),
                      pings = maps:remove(Pid, S#st.pings),
                      suspects = (S#st.suspects)#{Pid => now_ms() + ?SUSPECT_TTL}},
            Rest = lists:keydelete(Pid, 2, S1#st.left ++ S1#st.right),
            S2 = set_leaves(Rest ++ S1#st.nbr ++ maps:values(S1#st.rt), S1),
            InLeaf andalso repair_leaves(WasLeft, S2),
            [repair_rt(K, Desc, S2) || K <- Slots],
            S2
    end.

%% Leaf repair: ask the farthest live leaf on the affected side for its leaf
%% set (Section 2.4); fall back to the other side.
repair_leaves(WasLeft, S = #st{left = L, right = R}) ->
    Side = case WasLeft of true -> L; false -> R end,
    case Side ++ lists:reverse(L ++ R) of
        [] -> ok;
        _ when Side =/= [] -> send(element(2, lists:last(Side)), leafset_req, S);
        [D | _] -> send(element(2, D), leafset_req, S)
    end.

%% Routing table repair: ask another entry of the same row for its entry
%% in the failed slot (Section 2.4).
repair_rt({Row, Col}, _Failed, S = #st{rt = RT}) ->
    Others = [D || {{R, C}, D} <- maps:to_list(RT), R =:= Row, C =/= Col],
    Others2 = case Others of
                  [] -> [D || {{R, _}, D} <- maps:to_list(RT), R > Row];
                  _ -> Others
              end,
    case Others2 of
        [] -> ok;
        _ ->
            {_, P, _} = lists:nth(rand:uniform(length(Others2)), Others2),
            send(P, {rt_req, Row, Col}, S)
    end.

%% Keep-alive round: ping every leaf, declare unresponsive ones dead and
%% periodically refresh the leaf set from a random leaf.
keepalive(S = #st{left = L, right = R, pings = Pings}) ->
    Leaves = L ++ R,
    {Dead, Pings2} =
        lists:foldl(fun({_, P, _} = D, {Dd, Acc}) ->
                            Missed = maps:get(P, Pings, 0),
                            case Missed >= ?MAX_MISSED of
                                true -> {[D | Dd], Acc};
                                false ->
                                    send(P, {ping, make_ref()}, S),
                                    {Dd, Acc#{P => Missed + 1}}
                            end
                    end, {[], #{}}, Leaves),
    S2 = lists:foldl(fun node_failed/2, S#st{pings = Pings2}, Dead),
    case S2#st.left ++ S2#st.right of
        [] -> ok;
        Ls -> send(element(2, lists:nth(rand:uniform(length(Ls)), Ls)), leafset_req, S2)
    end,
    erlang:send_after(?KEEPALIVE, self(), keepalive),
    S2.

%%% ---------------------------------------------------------- application

app_apply(F, Args, S = #st{app = App, app_st = AS}) ->
    {AS2, Acts} = apply(App, F, Args ++ [ctx(S), AS]),
    lists:foldl(fun run_action/2, S#st{app_st = AS2}, Acts).

ctx(S) ->
    #{self => S#st.self, leaves => S#st.left ++ S#st.right,
      main => S#st.main, collector => S#st.collector, ft => S#st.ft}.

run_action({route, M, K}, S) ->
    do_route({app, M}, K, 0, S);
run_action({send, Pid, M}, S) ->
    send(Pid, {app_direct, M}, S),
    S;
run_action({timer, Ms, M}, S) ->
    erlang:send_after(Ms, self(), {app_timer, M}),
    S;
run_action({out, Pid, Term}, S) ->
    Pid ! Term,
    S.

%%% -------------------------------------------------------------- network

send(To, Body, S) ->
    case is_down(To, S) of
        true -> ok;
        false -> To ! {pastry, self(), Body}, ok
    end.

is_down(Pid, #st{down = D}) when map_size(D) =:= 0, is_pid(Pid) ->
    false;
is_down(Pid, #st{down = D}) ->
    case maps:find(Pid, D) of
        error -> false;
        {ok, infinity} -> true;
        {ok, Until} -> now_ms() < Until
    end.

now_ms() -> erlang:monotonic_time(millisecond).
