%%% pastry_util - identifier arithmetic for the Pastry overlay.
%%%
%%% NodeIds and keys are 128-bit unsigned integers on a circular id space
%%% [0, 2^128).  With b = 4 an id is read as a string of 32 hexadecimal digits,
%%% which is what the routing table is indexed by (row = length of the shared
%%% prefix, column = value of the next digit).
-module(pastry_util).

-export([hash_id/1, digit/2, shl/2, cw/2, dist/2, closer/3,
         hex/1, short/1, prox/2]).

-define(BITS, 128).
-define(DIGITS, 32).
-define(RING, (1 bsl ?BITS)).

%% 128-bit id from an arbitrary term (MD5, as a stand-in for SHA-1 in the paper).
hash_id(Term) ->
    <<I:?BITS/unsigned>> = crypto:hash(md5, term_to_binary(Term)),
    I.

%% Row-th hexadecimal digit of Id, most significant digit first (0-based).
digit(Id, Row) ->
    (Id bsr (?BITS - 4 * (Row + 1))) band 16#F.

%% Length (in hex digits) of the prefix shared by A and B.
shl(A, A) -> ?DIGITS;
shl(A, B) -> (?BITS - bitlen(A bxor B)) div 4.

bitlen(0) -> 0;
bitlen(X) when X >= (1 bsl 64) -> 64 + bitlen(X bsr 64);
bitlen(X) when X >= (1 bsl 16) -> 16 + bitlen(X bsr 16);
bitlen(X) when X >= 16 -> 4 + bitlen(X bsr 4);
bitlen(X) -> 1 + bitlen(X bsr 1).

%% Clockwise distance from From to To on the ring.
cw(From, To) -> (To - From + ?RING) rem ?RING.

%% Numerical (ring) distance between two ids.
dist(A, B) ->
    D = cw(A, B),
    min(D, ?RING - D).

%% true if id A is strictly closer to Key than id B (ties broken by id).
closer(A, B, Key) ->
    DA = dist(A, Key),
    DB = dist(B, Key),
    DA < DB orelse (DA =:= DB andalso A < B).

hex(Id) -> lists:flatten(io_lib:format("~32.16.0b", [Id])).

short(Id) -> lists:sublist(hex(Id), 8).

%% Proximity metric: euclidean distance between simulated network coordinates.
prox({X1, Y1}, {X2, Y2}) ->
    DX = X1 - X2,
    DY = Y1 - Y2,
    math:sqrt(DX * DX + DY * DY).
