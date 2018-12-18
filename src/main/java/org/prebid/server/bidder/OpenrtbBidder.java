package org.prebid.server.bidder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public abstract class OpenrtbBidder<T> implements Bidder<BidRequest> {

    private static final String DEFAULT_BID_CURRENCY = "USD";

    private final String endpointUrl;
    private final RequestCreationStrategy requestCreationStrategy;
    private final TypeReference<ExtPrebid<?, T>> extType = new TypeReference<ExtPrebid<?, T>>() {
    };

    protected OpenrtbBidder(String endpointUrl, RequestCreationStrategy requestCreationStrategy) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.requestCreationStrategy = Objects.requireNonNull(requestCreationStrategy);
    }

    @Override
    public final Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {
        try {
            validateRequest(bidRequest);
        } catch (PreBidException e) {
            return Result.emptyWithError(BidderError.badInput(e.getMessage()));
        }

        final List<BidderError> errors = new ArrayList<>();
        final List<ImpWithExt<T>> modifiedImpsWithExts = new ArrayList<>();
        for (Imp imp : bidRequest.getImp()) {
            final T impExt;
            try {
                validateImp(imp);
                impExt = parseImpExt(imp);
                validateImpExt(impExt, imp);
            } catch (PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
                continue;
            }
            modifiedImpsWithExts.add(new ImpWithExt<>(modifyImp(imp, impExt), impExt));
        }
        if (modifiedImpsWithExts.isEmpty()) {
            errors.add(BidderError.badInput("No valid impression in the bid request"));
            return Result.of(Collections.emptyList(), errors);
        }

        return Result.of(createHttpRequests(bidRequest, modifiedImpsWithExts), errors);
    }

    /**
     * A hook for bidder-specific request validation if any is required.
     * <p>
     * E.g., if bidder accepts only Site requests (not App), you might want to check
     * either request.site == null or request.app != null and throw PreBidException to terminate
     * processing invalid request:
     * <p>
     * if (bidRequest.getApp() != null) {
     * throw new PreBidException("Biddder doesn't support app requests);
     * }
     *
     * @param bidRequest - incoming request
     * @throws PreBidException - if request is invalid
     */
    protected void validateRequest(BidRequest bidRequest) throws PreBidException {
    }

    /**
     * A hook for bidder-specific single impression validation if any is required.
     * Note that the method is executed in a loop, therefore, if given impression
     * is invalid - throwing an exception will skip it.
     * <p>
     * E.g., if bidder accepts only banner requests - check if request.imp[i].banner !=null
     * <p>
     * if (imp.getBanner() == null) {
     * throw new PreBidException("Bidder supports only banner impressions. Skipping imp.id = xxx");
     * }
     *
     * @param imp - a single impression taken from incoming request
     * @throws PreBidException - if impression is invalid
     */
    protected void validateImp(Imp imp) throws PreBidException {
    }

    private T parseImpExt(Imp imp) {
        try {
            return Json.mapper.<ExtPrebid<?, T>>convertValue(imp.getExt(), extType).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage(), e);
        }
    }

    /**
     * A hook for bidder-specific imp.ext validation if any is required.
     * Note that the method is executed in the impressions loop, therefore,
     * if given extension is invalid - throwing an exception will skip its impression.
     * <p>
     * E.g., a client provided incorrect value for one of the parameters:
     * <p>
     * if (StringUtils.isBlank(impExt.getParameter())) {
     * throw new PreBidException("Invalid 'parameter' value. Skipping imp.id = xxx");
     * }
     *
     * @param impExt - a bidder-specific impression extension
     * @param imp    - current impression whose extension is being validated.
     *               Used to track the ID if impression to be skipped due to invalid extension
     * @throws PreBidException - if impression extension is invalid or contains errors
     */
    protected void validateImpExt(T impExt, Imp imp) throws PreBidException {
    }

    /**
     * A hook for bidder-specific impression changes if any are required.
     * <p>
     * By default - doesn't apply any changes.
     * <p>
     * Can be overridden to implement a part of bidder-specific request transformation logic,
     * such as setting some value from extension to impression itself, for example:
     * <p>
     * return imp.toBuilder()
     *           .id(impExt.getSpecialId)
     *           .build();
     * <p>
     * NOTE: It's not the only place to apply bidder-specific changes to impressions.
     * Additionally, there's an option to do these impressions' transformations later,
     * when modifying other outgoing request fields and properties.
     *
     * @param imp    - original request impression that can be modified
     * @param impExt - impression extension that can contain an information to modify its impression
     * @return - unmodified (default) or modified impression
     */
    protected Imp modifyImp(Imp imp, T impExt) {
        return imp;
    }

    private List<HttpRequest<BidRequest>> createHttpRequests(BidRequest bidRequest, List<ImpWithExt<T>> impsWithExts) {
        switch (requestCreationStrategy) {
            case REQUEST_PER_IMP:
                return impsWithExts.stream()
                        .map(impWithExt -> makeRequest(bidRequest, Collections.singletonList(impWithExt)))
                        .collect(Collectors.toList());
            case SINGLE_REQUEST:
                return Collections.singletonList(
                        makeRequest(bidRequest, impsWithExts));
            default:
                throw new IllegalArgumentException(String.format("Invalid request creation strategy: %s",
                        requestCreationStrategy));
        }
    }

    private HttpRequest<BidRequest> makeRequest(BidRequest bidRequest, List<ImpWithExt<T>> impsWithExts) {
        final BidRequest.BidRequestBuilder requestBuilder = bidRequest.toBuilder();
        final List<Imp> modifiedImps = impsWithExts.stream()
                .map(ImpWithExt::getImp)
                .collect(Collectors.toList());

        requestBuilder.imp(modifiedImps);

        final List<T> impExts = impsWithExts.stream()
                .map(ImpWithExt::getImpExt)
                .collect(Collectors.toList());

        modifyRequest(bidRequest, requestBuilder, modifiedImps, impExts);

        final BidRequest outgoingRequest = requestBuilder.build();
        final String body = Json.encode(outgoingRequest);

        return HttpRequest.<BidRequest>builder()
                .method(HttpMethod.POST)
                .uri(endpointUrl)
                .body(body)
                .headers(BidderUtil.headers())
                .payload(outgoingRequest)
                .build();
    }

    /**
     * A hook for any request changes other than Imps (although Impressions can be modified as well)
     * <p>
     * By default - applies no changes (other than imps, prior this method call)
     * <p>
     * Bidder-specific extensions might contain information not only for impressions' changes,
     * but also for other request fields, e.g. request.site.id, request.app.publisher.id, etc.
     * <p>
     * For example:
     * <p>
     * final String idFromExt = impExts.get(0).siteId;
     * requestBuilder.site(request.getSite().toBuilder().id(idFromExt).build());
     * <p>
     * Or if any extra changes are needed to already modified impressions:
     * <p>
     * final String placementId = impExts.get(0).getPlacementId();
     * final List<Imp> impsWithTagId = modifiedImps.stream()
     *          .map(Imp::toBuilder)
     *          .map(impBuilder -> impBuilder.tagid(placementId).build())
     *          .collect(Collectors.toList());
     * requestBuilder.imp(impsWithTagId);
     *
     * @param bidRequest     - original incoming bid request
     * @param requestBuilder - a builder to be used for modifying outgoing request,
     *                       received by calling bidRequest.toBuilder, i.e. contains incoming request information
     * @param modifiedImps   - a list of impressions that were already modified.
     * @param impExts        - bidder-specific impressions' extensions that could be used to modify request
     */
    protected void modifyRequest(BidRequest bidRequest, BidRequest.BidRequestBuilder requestBuilder,
                                 List<Imp> modifiedImps, List<T> impExts) {
    }

    @Override
    public final Result<List<BidderBid>> makeBids(HttpCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = Json.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return Result.of(extractBids(httpCall.getRequest().getPayload(), bidResponse), Collections.emptyList());
        } catch (DecodeException | PreBidException e) {
            return Result.emptyWithError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private List<BidderBid> extractBids(BidRequest bidRequest, BidResponse bidResponse) {
        if (bidResponse == null || bidResponse.getSeatbid() == null) {
            return Collections.emptyList();
        }
        return bidsFromResponse(bidRequest, bidResponse);
    }

    private List<BidderBid> bidsFromResponse(BidRequest bidRequest, BidResponse bidResponse) {
        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(bid -> BidderBid.of(bid, getBidType(bid.getImpid(), bidRequest.getImp()), getBidCurrency()))
                .collect(Collectors.toList());
    }

    /**
     * A hook for resolving bidder-specific bid type.
     * <p>
     * By default - checks request's imp[i].id and bid.impId first and then determines
     * bid type based on impression details with the following priority:
     * 1.Banner, 2.Video, 3.Native, 4.Audio.
     *
     * @param impId - impression id taken from bid.getImpid()
     * @param imps  - list of impressions from outgoing request
     * @return - bid type depending on impression's content
     */
    protected BidType getBidType(String impId, List<Imp> imps) {
        BidType bidType = BidType.banner;
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getBanner() != null) {
                    return bidType;
                } else if (imp.getVideo() != null) {
                    bidType = BidType.video;
                } else if (imp.getXNative() != null) {
                    bidType = BidType.xNative;
                } else if (imp.getAudio() != null) {
                    bidType = BidType.audio;
                }
            }
        }
        return bidType;
    }

    /**
     * A hook for defining a bid currency.
     * <p>
     * By default - USD.
     *
     * @return - bid currency
     */
    protected String getBidCurrency() {
        return DEFAULT_BID_CURRENCY;
    }

    @Override
    public final Map<String, String> extractTargeting(ObjectNode ext) {
        return Collections.emptyMap();
    }

    public enum RequestCreationStrategy {
        SINGLE_REQUEST,
        REQUEST_PER_IMP
    }

    @AllArgsConstructor
    @Value
    private static class ImpWithExt<T> {

        Imp imp;

        T impExt;
    }
}