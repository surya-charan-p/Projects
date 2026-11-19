# COP5612 Project 3: Pastry Overlay and Key/Value Service in Erlang

## Team members

- Surya Charan Perisetla

## How to run

Requirements: Erlang/OTP 26 or newer (tested on OTP 29).

```bash
./build.sh
```

```bash
./project3 numNodes numRequests
```

For example `./project3 1000 10`. The program builds a network of `numNodes` peers, each peer then makes `numRequests` requests at one request per second, and when every peer is done it prints

```
Average number of hops: 2.4752
```

along with a hop histogram and a check of routing correctness. If `ebin/` is missing, the wrapper compiles the code first.

Optional flags (the failure flags are for the bonus):

| flag | meaning |
|---|---|
| `--verify` | after the joins, compare every node's leaf set with the true ring neighbours |
| `--interval MS` | time between two requests of a peer (default 1000 ms, as the assignment asks) |
| `--seed S` | random seed, so a run can be reproduced |
| `--kill P` | crash P % of the peers during the request phase |
| `--link-temp P` / `--link-perm P` | cut one link of P % of the peers, temporarily or permanently |
| `--link-dur MS` | how long a temporary link failure lasts (default 3000 ms) |
| `--replicas K` | copies kept of every object (default 3) |
| `--no-ft` | inject failures but turn off the fault-tolerance mechanisms (used as a baseline) |
| `--csv FILE` | append one line of results to FILE |

## What is working

Everything in the assignment, plus the bonus.

- **Actor model.** Each peer is one Erlang process (`pastry_node`). Statistics go to a separate collector actor and failures come from an injector actor. The only parallelism is message passing between actors.
- **Pastry state** (paper §2.1, b = 4, 128-bit ids, 32 hex digits): a routing table (32 rows × 16 columns), a leaf set (|L| = 16, 8 smaller and 8 larger ids) and a neighborhood set (|M| = 16). Each node gets random 2-D coordinates that serve as the proximity metric. When two nodes compete for one routing-table slot, the closer one is kept, which gives Pastry's locality property.
- **Routing** follows Table 1 of the paper exactly:
  1. if the key is in the leaf-set range, go to the numerically closest leaf;
  2. otherwise use routing-table entry `R[l][D_l]`;
  3. if that entry is empty (the rare case), go to any known node that shares at least as long a prefix and is numerically closer.
- **Join** (paper §2.4). The new node X contacts a node A that is close to it in proximity. X's join message is routed toward key X. Every node on the path sends its state to X, and the final node Z (numerically closest to X) also supplies the leaf set. X then builds its tables and announces itself to every node in its state, and those nodes update their own tables. Nodes join one at a time. `--verify` confirms that 100 % of leaf sets equal the true ring neighbours at every size tested (10 to 20 000 nodes).
- **Pastry API** (paper §2.3). `pastry_node:start/5` is `pastryInit(Credentials, Application)` and returns the nodeId. `pastry_node:route/3` is `route(msg, key)`. The application module `kv_app` implements the upcalls `deliver(msg, key)`, `forward(msg, key, nextId)` and `newLeafs(leafSet)`.
- **Application** (`kv_app`). A simple object store that maps a key (in the same id space as the nodes) to a string.
  - Before the request phase, `put` loads up to 10 000 seed objects.
  - Each request is either a `get` of a random seed object, where the returned string is checked against the expected one, or a `put` of a new string.
  - The root node replies directly to the requester and replicates each object on its K−1 closest leaves, as PAST does.
- **Output.** The average number of hops per request, a hop histogram, and the share of requests whose answering node really is the live node numerically closest to the key. That share was 100 % in every failure-free run.

Measured average hops (3 trials, 10 requests per peer):

| N | 10 | 100 | 1 000 | 5 000 | 10 000 | 20 000 | 100 000 | 200 000 |
|---|---|---|---|---|---|---|---|---|
| avg hops | 0.91 | 1.70 | 2.48 | 2.96 | 3.24 | 3.50 | 3.99 | 4.27 |
| log₁₆ N | 0.83 | 1.66 | 2.49 | 3.07 | 3.32 | 3.57 | 4.15 | 4.40 |

The hop count stays just under log₁₆ N, as the paper predicts. The full write-up of the design and these results is in **`Report.pdf`**.

## Implementation decisions and gaps in the paper

The paper leaves a number of details open, or assumes things a simulator does not have. For each one, this section says what the paper gives, what is missing, and what this implementation does.

### Parameters and identifiers

- **b, |L| and |M|.** The paper suggests b = 4 and gives |L| and |M| as 2^b or 2 × 2^b. We use b = 4, |L| = 16 and |M| = 16.
- **How ids are made.** The paper hashes a node's IP address or public key with SHA-1 and uses 128 bits of the result. A simulated node has neither, so its id is the MD5 hash (128 bits) of its index plus a per-run random salt. Object keys are hashed the same way. Only the uniform spread of ids matters to Pastry, and MD5 gives that.
- **Ties.** The paper does not say what to do when two nodes are equally close to a key. We pick the smaller id, so that every node makes the same choice.

### Proximity and locality

- **What "close" means.** The paper assumes a scalar proximity metric such as IP hops or round-trip time, which a node can measure. A simulator has no real network, so each node gets random coordinates in a 1000 × 1000 plane and proximity is the euclidean distance between them.
- **Finding a nearby node to join through.** The paper assumes the new node X already knows a nearby node A (for example through expanding-ring IP multicast). We give X the nearest of 32 randomly sampled nodes that have already joined. Searching all joined nodes would make joining quadratic at 100 000 nodes.
- **Using proximity in the tables.** When two nodes qualify for the same routing-table slot, the one closer by proximity is kept. The neighborhood set holds the 16 closest nodes seen. This is what gives Pastry its locality property.

### Join

- **Which rows X takes from which node.** The paper says X takes row 0 from A, row 1 from the second node on the path, and so on. This assumes the i-th node shares exactly i digits with X, which is not always true. Instead, X takes every entry from every node on the path and puts each one in the slot where it belongs in X's own table. This fills the table at least as fully as the paper's rule, and never puts a node in a wrong slot.
- **Knowing when the join is finished.** The paper does not say how X knows it has heard from everyone on the path. The last node Z reports the path length, so X waits until it has that many replies. X then announces itself to every node in its tables and finishes joining when all of them have acknowledged.
- **Concurrent joins.** The paper only sketches how nodes that join at the same time are kept consistent. We join nodes one at a time, and the next join starts only after the previous one is acknowledged. This is the reason `--verify` finds every leaf set correct. The cost is that joining 200 000 nodes takes about 10 minutes.

### Leaf set and routing

- **Networks smaller than the leaf set.** The paper assumes there are more than |L| nodes. With 16 or fewer other nodes, every node knows all the others, so we put each one on whichever side of the ring is closer and treat every key as inside the leaf-set range.
- **The leaf-set range on a ring.** The paper writes the range test as L₋ ≤ D ≤ L₊, which ignores wrap-around at the end of the id space. We measure distances clockwise and counter-clockwise around the ring, so the test also works across the wrap point.
- **The rare case.** Rule 3 of the routing procedure lets a node forward to *any* known node that shares a long enough prefix and is numerically closer to the key. We pick the numerically closest such node.
- **Counting hops.** A hop is one transfer of a message from one node to the next. A request whose requester is already the root counts as 0 hops. The root sends its answer straight back to the requester (as PAST does), and that answer is not counted. Join and seeding traffic is not counted either.

### Application and workload

- **The application.** The assignment leaves the application open. We implement a store that maps a key to a string, with `put` and `get`.
- **The request mix.** Before the request phase, min(N, 10 000) objects (at least 16) are stored. Each request is then, with equal probability, a `get` of a random stored object, whose returned string is checked against the expected one, or a `put` of a new string.
- **Request timing.** Each peer sends one request per second. Its first request is delayed by a random fraction of a second, so that not all peers send at the same instant.
- **Ending the run.** The program ends when every peer has received answers to all of its requests (or has been killed in a failure run).

### Simulator

- **No network delay.** Messages between actors arrive as fast as Erlang delivers them, so hop count is the measure of cost, not time.
- **Speed at the largest sizes.** At 100 000 nodes and above, the machine cannot keep up with one request per second per peer, so the request phase takes longer than `numRequests` seconds. All requests still complete.

### Failures (bonus)

- **Timing values.** The paper says leaf sets are checked with keep-alive messages but gives no numbers. We chose these values:

  | setting | value |
  |---|---|
  | wait for a hop acknowledgement | 400 ms |
  | keep-alive interval | 1 s |
  | missed keep-alives before a node is declared dead | 2 |
  | time a failed node is kept out of the tables | 10 s |
  | requester timeout | 3 s |
  | attempts per request | 3 |

- **Repairing the routing table.** The paper says to ask another entry in the same row for a replacement, and to try later rows if that row is empty. We do exactly that.
- **Replication.** We follow PAST: each object is stored on the K nodes closest to its key (K = 3 by default). Whenever `newLeafs` reports that a node's leaf set has changed, every object that node is now root of is copied again to its current replica set.
- **Not modelled.** We do not model nodes that leave on purpose, malicious nodes, or the randomized routing the paper proposes against them. As in the paper, data and routing can still break if all copies of an object, or all L/2 leaves on one side of a node, fail before repair happens.

## Largest network

**200 000 nodes** (`./project3 200000 2`):

- all 400 000 requests answered correctly by the correct root node;
- average 4.27 hops;
- the join phase took about 10 minutes and the memory use was 10.4 GB (max resident set size) on a 14-core, 24 GB laptop.

100 000 nodes needs about 6.6 GB (max resident set size) and joins in 100 s. Beyond 200 000 the limit is memory: each actor keeps about 100 node descriptors.

At 100 000 nodes and above the machine cannot keep up with one request per second per peer, because that is 100 000+ routed messages per second plus replication. The requests are queued and the request phase takes longer than `numRequests` seconds, but every request still completes.

## Bonus: failure model

Use `--kill`, `--link-temp`, `--link-perm` and `--no-ft`. The full write-up is in **`Report-bonus.pdf`**. Briefly:

**Failure models**
- node crash: the process is killed;
- link failure, temporary or permanent: both ends drop everything sent over that one link.

**Mechanisms**
- detection:
  - each hop of a routed message is acknowledged, and a missing ack causes a reroute;
  - leaf-set keep-alives;
  - the requester times out and retries;
- repair, as in the paper:
  - a broken leaf set asks the farthest live leaf for its leaf set;
  - a broken routing-table slot asks another entry in the same row;
- data survival: K-way replication with re-replication from `newLeafs`.

**Findings** (1000 nodes; with fault tolerance on versus off):

| failure | answered correctly, FT on | answered correctly, FT off |
|---|---|---|
| 30 % of nodes crashed | 99.8 % | 66 % |
| 40 % of nodes crashed | 99.3 % | 60 % |
| 40 % of nodes with a cut link (temporary) | 100 % | 98.9 % |
| 40 % of nodes with a cut link (permanent) | 99.9 % | 97.5 % |

- The average hop count rises by less than 0.2.
- With K = 1 replica, 8.3 % of reads find their object lost at 30 % crashes. With K = 5 none do.

## Files

- `src/pastry_node.erl`: the Pastry peer actor (state, join, routing, failure detection and repair)
- `src/kv_app.erl`: the key/value application (Pastry upcalls and request generator)
- `src/pastry_util.erl`: id arithmetic (digits, shared prefix, ring distance)
- `src/pastry_collector.erl`: the statistics actor (also checks that each request was routed to the right node)
- `src/project3.erl`: the entry point (parses arguments, joins nodes, seeds the store, injects failures, prints results)
- `project3`, `build.sh`: the run wrapper and the build script
- `Report.pdf`: the main project report
- `Report-bonus.pdf`: the bonus report
