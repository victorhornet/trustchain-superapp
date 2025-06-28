# Usage Logging for Transaction Benchmarks

## High-Level Overview

```text
┌───────────────────────────────────────────────────────────────┐
│                    UI Layer                                   │
│  ┌────────────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ BenchmarksFragment │  │ PieChartView │  │ LegendAdapter │  │
│  └────────────────────┘  └──────────────┘  └───────────────┘  │
├───────────────────────────────────────────────────────────────┤
│                  Business Logic Layer                         │
│  ┌─────────────────┐  ┌─────────────────┐  ┌───────────────┐  │
│  │UsageBenchmark   │  │Transaction      │  │ Transaction   │  │
│  │Calculator       │  │Checkpoint       │  │ Breakdown     │  │
│  └─────────────────┘  └─────────────────┘  └───────────────┘  │
├───────────────────────────────────────────────────────────────┤
│                   Data Access Layer                           │
│  ┌─────────────────┐  ┌─────────────────┐                     │
│  │ UsageEventsDao  │  │ UsageLogger     │                     │
│  └─────────────────┘  └─────────────────┘                     │
├───────────────────────────────────────────────────────────────┤
│                  Persistence Layer                            │
│  ┌─────────────────┐  ┌─────────────────┐                     │
│  │ Room Database   │  │ Event Models    │                     │
│  └─────────────────┘  └─────────────────┘                     │
└───────────────────────────────────────────────────────────────┘
```

## Core components

### Room Database
<!-- how is the data stored? -->

### Data Models

**transaction events**
represents monetary tranactions e.g. sending 2 euros to someone. starts when you click "Send", ends when you payment is confirmed.
TransactionStartEvent - start of a transaction
TransactionErrorEvent - if the transaction errors
TransactionCancelEvent - if the transaction is canceled (didnt finish)
TransactionDoneEvent - if the transaction finished successfully

**transaction checkpoint events**
represents the phases of each transaction for granular timings. they have a label, which identifies tem (e.g. `create_proposal_block`). useful to know which steps in a transaction take the most time, etc.
utility classes for creating these vents in TransactionCheckpoints.kt

**transfer events**
represents one instance of nfc data transfer (transcieve). tracks the payload size, start and end times. used to calculated nfc throughput and error rate.

etc.

### UsageEventsDao
<!-- What is it, how is it used? -->
defines the queries for creating and retrieving events...

### UsageLogger

**Purpose**: Central singleton for collecting performance events throughout the application lifecycle.

**Key Features**:

- Thread-safe coroutine-based logging
- Transaction state management (prevents orphaned events)
- Hierarchical event structure (Transaction → Transfer → Checkpoint)
- Automatic transaction ID generation and tracking

**Usage Pattern**:

```kotlin
// Start transaction logging
val transactionId = UsageLogger.logTransactionStart(payload)

// Track specific phases with checkpoints
trackCheckpoint("creating connection") {
    // Connection establishment code
}

// Log transfer operations
val transferId = UsageLogger.logTransferStart(TransferDirection.OUTBOUND, payloadSize)
// ... transfer logic
UsageLogger.logTransferDone(receivedBytes)

// Complete transaction
UsageLogger.logTransactionDone()
```

### Usage Analytics
<!-- how are the benchmarsk calculated from the lgos and displayed -->
UsageBenchmarkCalculator - classes and methods for computing the benchmark rtesults from event logs
TransactionBreakdown - data classes and methods to prepare the benchmarks for displaying them
the other ui classes used to show the results

## Experiment Setup

### Realistic usage tests

1. stable use case (send 0.2 euro) x15

- it was done live with 2 devices (no emulators)
- airplane mode turned on
- keep the receiving phone on the table so its stable
- put the sending phone on top so its stable until transaction finishes

2. "quick tap" test x10
simulates more realisting usage between 2 people

- same as 1, but the phones are held in the hand at all time

3. "phone moved too fast" test x5

- same general setup as 2
- except the phones are also quickly swiped to see how easy it is to error it

### Data transfer limit testing

1. limit tesing use case (add extra payload bytes to test limits of data transfer)
execute one transaction with a payload of 1MB, to see how much time it takes to transfer

## Results

### Realistic Usage Test Results
<!-- Here i will add a screenshot of the benchmark results -->

### Data Transfer Limit Testing
<!-- Here i will add a video of the transaction crashes -->
the transaction halted after 2 minutes 30 seconds. phones were overheating.

## Future Work

currently all the logs sre recomputed every time the benchmark screen is opened, which can get laggy if there are a lot of transactions and transfers done. simple improvement would be to cache the values at different checkpoints on every open, to only compute the new logs
