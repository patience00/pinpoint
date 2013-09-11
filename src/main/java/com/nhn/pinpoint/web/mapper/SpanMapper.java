package com.nhn.pinpoint.web.mapper;

import com.nhn.pinpoint.common.bo.AnnotationBo;
import com.nhn.pinpoint.common.bo.SpanBo;
import com.nhn.pinpoint.common.bo.SpanEventBo;
import com.nhn.pinpoint.common.hbase.HBaseTables;
import com.nhn.pinpoint.web.vo.TransactionId;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.hadoop.hbase.RowMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 *
 */
@Component
public class SpanMapper implements RowMapper<List<SpanBo>> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private AnnotationMapper annotationMapper;

    public AnnotationMapper getAnnotationMapper() {
        return annotationMapper;
    }

    public void setAnnotationMapper(AnnotationMapper annotationMapper) {
        this.annotationMapper = annotationMapper;
    }

    @Override
    public List<SpanBo> mapRow(Result result, int rowNum) throws Exception {
        byte[] rowKey = result.getRow();

        if (rowKey == null) {
            return Collections.emptyList();
        }
        final TransactionId transactionId = new TransactionId(rowKey, TransactionId.DISTRIBUTE_HASH_SIZE);

        KeyValue[] keyList = result.raw();
        List<SpanBo> spanList = new ArrayList<SpanBo>();
        Map<Integer, SpanBo> spanMap = new HashMap<Integer, SpanBo>();
        List<SpanEventBo> spanEventBoList = new ArrayList<SpanEventBo>();
        for (KeyValue kv : keyList) {
            // family name "span"일때로만 한정.
            byte[] family = kv.getFamily();
            if (Bytes.equals(family, HBaseTables.TRACES_CF_SPAN)) {

                SpanBo spanBo = new SpanBo();
                spanBo.setTraceAgentId(transactionId.getAgentId());
                spanBo.setTraceAgentStartTime(transactionId.getAgentStartTime());
                spanBo.setTraceTransactionSequence(transactionId.getTransactionSequence());
                spanBo.setCollectorAcceptTime(kv.getTimestamp());

                spanBo.setSpanID(Bytes.toInt(kv.getBuffer(), kv.getQualifierOffset()));
                spanBo.readValue(kv.getBuffer(), kv.getValueOffset());
                if (logger.isDebugEnabled()) {
                    logger.debug("read span :{}", spanBo);
                }
                spanList.add(spanBo);
                spanMap.put(spanBo.getSpanId(), spanBo);
            } else if (Bytes.equals(family, HBaseTables.TRACES_CF_TERMINALSPAN)) {
                SpanEventBo spanEventBo = new SpanEventBo();
                spanEventBo.setTraceAgentId(transactionId.getAgentId());
                spanEventBo.setTraceAgentStartTime(transactionId.getAgentStartTime());
                spanEventBo.setTraceTransactionSequence(transactionId.getTransactionSequence());

                int spanId = Bytes.toInt(kv.getBuffer(), kv.getQualifierOffset());
                // 앞의 spanid가 int이므로 4.
                final int spanIdOffset = 4;
                short sequence = Bytes.toShort(kv.getBuffer(), kv.getQualifierOffset() + spanIdOffset);
                spanEventBo.setSpanId(spanId);
                spanEventBo.setSequence(sequence);

                spanEventBo.readValue(kv.getBuffer(), kv.getValueOffset());
                if (logger.isDebugEnabled()) {
                    logger.debug("read spanEvent :{}", spanEventBo);
                }
                spanEventBoList.add(spanEventBo);
            }
        }
        for (SpanEventBo spanEventBo : spanEventBoList) {
            SpanBo spanBo = spanMap.get(spanEventBo.getSpanId());
            if (spanBo != null) {
                spanBo.addSpanEvent(spanEventBo);
            }
        }
        if (annotationMapper != null) {
            Map<Integer, List<AnnotationBo>> annotationMap = annotationMapper.mapRow(result, rowNum);
            addAnnotation(spanList, annotationMap);
        }


        return spanList;

    }

    private void addAnnotation(List<SpanBo> spanList, Map<Integer, List<AnnotationBo>> annotationMap) {
        for (SpanBo bo : spanList) {
            int spanID = bo.getSpanId();
            List<AnnotationBo> anoList = annotationMap.get(spanID);
            bo.setAnnotationBoList(anoList);
        }
    }
}
