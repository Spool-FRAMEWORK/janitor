package software.spool.janitor.api.fixture;

import software.spool.core.adapter.jackson.PayloadDeserializerFactory;
import software.spool.core.exception.QuarantinedResultRejectedException;
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.port.quarantine.QuarantinedResultWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A data mart partitioned by one attribute of each result, which keeps what is written to it in memory.
 */
public class FakeDataMart implements QuarantinedResultWriter {
    public record WrittenResult(PartitionKey partition, Map<String, Object> content) {}

    private final String dataMart;
    private final List<WrittenResult> written = new ArrayList<>();
    private String partitionAttribute;

    public FakeDataMart(String dataMart, String partitionAttribute) {
        this.dataMart = dataMart;
        this.partitionAttribute = partitionAttribute;
    }

    public void partitionBy(String attribute) {
        this.partitionAttribute = attribute;
    }

    public List<WrittenResult> written() {
        return List.copyOf(written);
    }

    @Override
    public String dataMart() {
        return dataMart;
    }

    @Override
    public void write(PartitionKey partition, byte[] content) {
        Map<String, Object> result = PayloadDeserializerFactory.json().asMap().deserialize(content);
        Object value = result.get(partitionAttribute);
        if (value == null) {
            throw new QuarantinedResultRejectedException("The result cannot be written to " + dataMart
                    + ": Partition attribute " + partitionAttribute + " is missing or null in the mount result", null);
        }
        written.add(new WrittenResult(PartitionKey.ofEntriesWithoutDate().with(partitionAttribute, String.valueOf(value)).build(), result));
    }
}
