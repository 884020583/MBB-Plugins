package org.moboxlab.mbb.eval.call;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.moboxlab.mbb.eval.store.EvalSchemaService;
import org.moboxlab.mbb.eval.store.EvalStorageFake;
import org.moboxlab.mbb.eval.store.EvalStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 预算硬顶记账测试（规格 §4.4、§9.5、§13.3）。
 *
 * <p>验证 calls_used 先原子递增再写穿、达到预算后停止后续调用并标记 budget，
 * 以及并发下不超卖。</p>
 */
public class EvalBudgetLedgerTest {

    private static final String RUN_ID = "r-20261026120000-smoke-0002";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private EvalStorageFake storage;

    @Before
    public void setUp() throws Exception {
        File dbFile = temporaryFolder.newFile("eval-budget.db");
        storage = new EvalStorageFake(dbFile);
        assertTrue("建表失败", new EvalSchemaService(storage, null).ensureSchema());
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    public void limitShouldAllowExactlyThatManyCallsThenStop() {
        CountingWriter writer = new CountingWriter();
        EvalBudgetLedger ledger = new EvalBudgetLedger(RUN_ID, 5, 3, 0L, writer, null);

        assertEquals(0, ledger.used());
        assertEquals(3, ledger.remaining());
        assertEquals(EvalBudgetLedger.Decision.CONSUMED, ledger.consume());
        assertEquals(EvalBudgetLedger.Decision.CONSUMED, ledger.consume());
        assertEquals(EvalBudgetLedger.Decision.CONSUMED, ledger.consume());
        assertEquals(3, ledger.used());
        assertEquals(0, ledger.remaining());
        assertEquals("每次成功调用都要写穿一次", 3, writer.calls.get());

        assertEquals(EvalBudgetLedger.Decision.STOP, ledger.consume());
        assertEquals(3, ledger.used());
        assertEquals("STOP 不再写穿", 3, writer.calls.get());
        assertTrue(ledger.isAborted());
        assertEquals(EvalStore.ABORT_REASON_BUDGET, ledger.abortReason());

        assertEquals(EvalBudgetLedger.Decision.STOP, ledger.consume());
        assertEquals(3, writer.calls.get());
    }

    @Test
    public void writeThroughFailureShouldNotBlockCall() {
        CountingWriter writer = new CountingWriter();
        writer.result = false;
        EvalBudgetLedger ledger = new EvalBudgetLedger(RUN_ID, 2, 1, 0L, writer, null);

        assertEquals(EvalBudgetLedger.Decision.CONSUMED, ledger.consume());
        assertEquals(1, ledger.used());
        assertEquals(1, writer.calls.get());

        assertEquals(EvalBudgetLedger.Decision.STOP, ledger.consume());
        assertEquals(EvalStore.ABORT_REASON_BUDGET, ledger.abortReason());
    }

    @Test
    public void concurrentConsumeShouldNotOversell() throws Exception {
        final int limit = 7;
        CountingWriter writer = new CountingWriter();
        final EvalBudgetLedger ledger = new EvalBudgetLedger(RUN_ID, 20, limit, 0L, writer, null);
        final AtomicInteger consumed = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        for (int j = 0; j < 20; j++) {
                            if (ledger.consume() == EvalBudgetLedger.Decision.CONSUMED) {
                                consumed.incrementAndGet();
                            }
                        }
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals("并发下不得超卖", limit, consumed.get());
        assertEquals(limit, ledger.used());
        assertEquals("写穿次数必须等于成功次数", limit, writer.calls.get());
        assertEquals(EvalStore.ABORT_REASON_BUDGET, ledger.abortReason());
    }

    @Test
    public void storeBackedLedgerShouldWriteCallsUsedToRunRow() {
        EvalStore store = new EvalStore(storage, null);
        assertTrue(store.insertRunning(RUN_ID, EvalStore.TIER_SMOKE, "2026-10-26 12:00:00", 1, null));
        EvalBudgetLedger ledger = new EvalBudgetLedger(RUN_ID, 1, 1, 0L, store, null);

        assertEquals(EvalBudgetLedger.Decision.CONSUMED, ledger.consume());
        JSONObject run = store.findRun(RUN_ID);
        assertEquals(1, run.getIntValue("calls_used"));

        assertEquals(EvalBudgetLedger.Decision.STOP, ledger.consume());
        run = store.findRun(RUN_ID);
        assertEquals("STOP 后不得继续累加", 1, run.getIntValue("calls_used"));
        assertEquals("budget", ledger.abortReason());
        assertFalse("写穿必须走 calls_used 自增语句", storage.updateSql().isEmpty());
        boolean incremented = false;
        for (String sql : storage.updateSql()) {
            if (sql.contains("calls_used = calls_used + 1")) {
                incremented = true;
            }
        }
        assertTrue("必须执行 calls_used 自增", incremented);
    }

    /** 计数写穿器：记录调用次数，并可按需模拟写库失败。 */
    private static final class CountingWriter implements EvalBudgetLedger.CallsUsedWriter {
        private final AtomicInteger calls = new AtomicInteger();
        private boolean result = true;

        @Override
        public boolean incrementCallsUsed(String runId) {
            calls.incrementAndGet();
            return result;
        }
    }
}
