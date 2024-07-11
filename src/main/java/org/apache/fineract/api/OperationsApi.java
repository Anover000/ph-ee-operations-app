package org.apache.fineract.api;


import com.amazonaws.services.fms.model.InvalidInputException;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.apache.fineract.operations.BatchRepository;
import org.apache.fineract.operations.BusinessKey;
import org.apache.fineract.operations.BusinessKeyRepository;
import org.apache.fineract.operations.DelayResponseDTO;
import org.apache.fineract.operations.EventTimestamps;
import org.apache.fineract.operations.EventTimestampsRepository;
import org.apache.fineract.operations.Task;
import org.apache.fineract.operations.TaskRepository;
import org.apache.fineract.operations.TransactionRequest;
import org.apache.fineract.operations.TransactionRequestDetail;
import org.apache.fineract.operations.TransactionRequestRepository;
import org.apache.fineract.operations.Transfer;
import org.apache.fineract.operations.TransferDetail;
import org.apache.fineract.operations.TransferRepository;
import org.apache.fineract.operations.TransferStatus;
import org.apache.fineract.operations.Variable;
import org.apache.fineract.operations.VariableRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RestController
@SecurityRequirement(name = "auth")
@RequestMapping("/api/v1")
public class OperationsApi {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private BusinessKeyRepository businessKeyRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransactionRequestRepository transactionRequestRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private EventTimestampsRepository eventTimestampsRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${channel-connector.url}")
    private String channelConnectorUrl;

    @Value("${channel-connector.transfer-path}")
    private String channelConnectorTransferPath;

    @PostMapping("/transfer/{transactionId}/refund")
    public String refundTransfer(@RequestHeader("Platform-TenantId") String tenantId,
                                 @PathVariable("transactionId") String transactionId,
                                 @RequestBody String requestBody,
                                 HttpServletResponse response) {
        Transfer existingIncomingTransfer = transferRepository.findFirstByTransactionIdAndDirection(transactionId, "INCOMING");
        if (existingIncomingTransfer == null || !TransferStatus.COMPLETED.equals(existingIncomingTransfer.getStatus())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            JSONObject failResponse = new JSONObject();
            failResponse.put("response", "Requested incoming transfer does not exist or not yet completed!");
            return failResponse.toString();
        }

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Platform-TenantId", tenantId);
        httpHeaders.add("Content-Type", "application/json");
        // httpHeaders.add("Authorization", "Bearer token"); TODO auth needed?

        JSONObject channelRequest = new JSONObject();
        JSONObject payer = new JSONObject();
        JSONObject payerPartyIdInfo = new JSONObject();
        payerPartyIdInfo.put("partyIdType", existingIncomingTransfer.getPayeePartyIdType());
        payerPartyIdInfo.put("partyIdentifier", existingIncomingTransfer.getPayeePartyId());
        payer.put("partyIdInfo", payerPartyIdInfo);
        channelRequest.put("payer", payer);
        JSONObject payee = new JSONObject();
        JSONObject payeePartyIdInfo = new JSONObject();
        payeePartyIdInfo.put("partyIdType", existingIncomingTransfer.getPayerPartyIdType());
        payeePartyIdInfo.put("partyIdentifier", existingIncomingTransfer.getPayerPartyId());
        payee.put("partyIdInfo", payeePartyIdInfo);
        channelRequest.put("payee", payee);
        JSONObject amount = new JSONObject();
        amount.put("amount", existingIncomingTransfer.getAmount());
        amount.put("currency", existingIncomingTransfer.getCurrency());
        channelRequest.put("amount", amount);
        try {
            JSONObject body = new JSONObject(requestBody);
            String comment = body.optString("comment", null);
            if (comment != null) {
                JSONObject extensionList = new JSONObject();
                JSONArray extensions = new JSONArray();
                addExtension(extensions, "comment", comment);
                extensionList.put("extension", extensions);
                channelRequest.put("extensionList", extensionList);
            }
        } catch (Exception e) {
            logger.error("Could not parse refund request body {}, can not set comment on refund!", requestBody);
        }

        ResponseEntity<String> channelResponse = restTemplate.exchange(channelConnectorUrl + channelConnectorTransferPath,
                HttpMethod.POST,
                new HttpEntity<String>(channelRequest.toString(), httpHeaders),
                String.class);
        response.setStatus(channelResponse.getStatusCodeValue());
        return channelResponse.getBody();
    }

    private void addExtension(JSONArray extensionList, String key, String value) {
        JSONObject extension = new JSONObject();
        extension.put("key", key);
        extension.put("value", value);
        extensionList.put(extension);
    }

    @GetMapping("/transfer/{workflowInstanceKey}")
    public TransferDetail transferDetails(@PathVariable Long workflowInstanceKey) {
        Transfer transfer = transferRepository.findFirstByWorkflowInstanceKey(workflowInstanceKey);
        List<Task> tasks = taskRepository.findByWorkflowInstanceKeyOrderByTimestamp(workflowInstanceKey);
        List<Variable> variables = variableRepository.findByWorkflowInstanceKeyOrderByTimestamp(workflowInstanceKey);
        return new TransferDetail(transfer, tasks, variables);
    }

    @GetMapping("/transactionRequest/{workflowInstanceKey}")
    public TransactionRequestDetail transactionRequestDetails(@PathVariable Long workflowInstanceKey) {
        TransactionRequest transactionRequest = transactionRequestRepository.findFirstByWorkflowInstanceKey(workflowInstanceKey);
        List<Task> tasks = taskRepository.findByWorkflowInstanceKeyOrderByTimestamp(workflowInstanceKey);
        List<Variable> variables = variableRepository.findByWorkflowInstanceKeyOrderByTimestamp(workflowInstanceKey);
        return new TransactionRequestDetail(transactionRequest, tasks, variables);
    }

    @GetMapping("/variables")
    public List<List<Variable>> variables(
            @RequestParam(value = "businessKey") String businessKey,
            @RequestParam(value = "businessKeyType") String businessKeyType
    ) {
        return loadTransfers(businessKey, businessKeyType).stream()
                .map(transfer -> variableRepository.findByWorkflowInstanceKeyOrderByTimestamp(transfer.getWorkflowInstanceKey()))
                .collect(Collectors.toList());
    }

    @GetMapping("/variables/{transactionId}")
    public Map<String, String> variablesList( @PathVariable String transactionId) {
        Long workflowInstanceKey = transferRepository.findFirstByTransactionId(transactionId)
                .map(Transfer::getWorkflowInstanceKey)
                .orElseGet(() -> Long.valueOf(transactionRequestRepository.findFirstByTransactionId(transactionId)
                        .map(TransactionRequest::getWorkflowInstanceKey)
                        .orElseThrow(() -> new InvalidInputException("Transaction Id does not exist"))));
        HashMap<String, String> variables = new HashMap<>();
        variableRepository.findByWorkflowInstanceKeyOrderByTimestamp(workflowInstanceKey).forEach(variable -> {
            variables.put(variable.getName(), variable.getValue());
        });
        return variables;
    }

    @GetMapping("/tasks")
    public List<List<Task>> tasks(
            @RequestParam(value = "businessKey") String businessKey,
            @RequestParam(value = "businessKeyType") String businessKeyType
    ) {
        return loadTransfers(businessKey, businessKeyType).stream()
                .map(transfer -> taskRepository.findByWorkflowInstanceKeyOrderByTimestamp(transfer.getWorkflowInstanceKey()))
                .collect(Collectors.toList());
    }

    private List<BusinessKey> loadTransfers(@RequestParam("businessKey") String
                                                    businessKey, @RequestParam("businessKeyType") String businessKeyType) {
        List<BusinessKey> businessKeys = businessKeyRepository.findByBusinessKeyAndBusinessKeyType(businessKey, businessKeyType);
        logger.debug("loaded {} transfer(s) for business key {} of type {}", businessKeys.size(), businessKey, businessKeyType);
        return businessKeys;
    }

    @GetMapping("/delays")
    public DelayResponseDTO getDelays() {

        List<EventTimestamps> timestamps =  eventTimestampsRepository.findAll();
        AtomicReference<Long> totalExportImportDiff = new AtomicReference<>(0L);
        AtomicReference<Long> totalZeebeExportDiff = new AtomicReference<>(0L);
        int eventsCount = timestamps.size();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ");
        timestamps.parallelStream().forEach(timestamp -> {
            try {
                Date d1 = sdf.parse(timestamp.getExportedTime());
                Date d2 = sdf.parse(timestamp.getImportedTime());
                Date d3 = new Date(Long.parseLong(timestamp.getZeebeTime()));
                totalExportImportDiff.updateAndGet(v -> v + d2.getTime()-d1.getTime());
                totalZeebeExportDiff.updateAndGet(v -> v + d1.getTime() - d3.getTime());
            }
            catch (ParseException e) {
                    throw new RuntimeException(e);
            }
        });

        DelayResponseDTO delayResponseDTO;
        if(eventsCount!=0) {
            delayResponseDTO = new DelayResponseDTO(totalExportImportDiff.get()/eventsCount, totalZeebeExportDiff.get()/eventsCount, eventsCount);
        }
        else {
            delayResponseDTO = new DelayResponseDTO();
        }
        return delayResponseDTO;
    }

    @DeleteMapping("/delays")
    public void deleteTimestampsRecords() {
        eventTimestampsRepository.deleteAllTimestamps();
    }

    @PostMapping("/count/{status}")
    public ResponseEntity<Long> countTransactionsWithTheGivenStatus(@PathVariable String status, @RequestBody List<String> transactionIds) {
        try {
            Long count = transferRepository.countCompletedTransactions(transactionIds, TransferStatus.valueOf(status.toUpperCase()));
            return new ResponseEntity<>(count, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }
}
