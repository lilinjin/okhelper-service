package com.ok.okhelper.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.ok.okhelper.common.PageModel;
import com.ok.okhelper.dao.CustomerMapper;
import com.ok.okhelper.dao.ProductMapper;
import com.ok.okhelper.dao.SaleOrderDetailMapper;
import com.ok.okhelper.dao.SaleOrderMapper;
import com.ok.okhelper.exception.IllegalException;
import com.ok.okhelper.exception.NotFoundException;
import com.ok.okhelper.common.constenum.ConstEnum;
import com.ok.okhelper.common.constenum.ConstStr;
import com.ok.okhelper.pojo.dto.PaymentDto;
import com.ok.okhelper.pojo.dto.PlaceOrderDto;
import com.ok.okhelper.pojo.dto.PlaceOrderItemDto;
import com.ok.okhelper.pojo.dto.SaleOrderDto;
import com.ok.okhelper.pojo.po.Customer;
import com.ok.okhelper.pojo.po.Product;
import com.ok.okhelper.pojo.po.SaleOrder;
import com.ok.okhelper.pojo.po.SaleOrderDetail;
import com.ok.okhelper.pojo.vo.PlaceOrderVo;
import com.ok.okhelper.pojo.vo.ProductCountMapVo;
import com.ok.okhelper.pojo.vo.SaleOrderVo;
import com.ok.okhelper.pojo.vo.SaleTotalVo;
import com.ok.okhelper.service.OtherService;
import com.ok.okhelper.service.ProductService;
import com.ok.okhelper.service.SaleService;
import com.ok.okhelper.shiro.JWTUtil;
import com.ok.okhelper.util.AliPayUtil;
import com.ok.okhelper.util.NumberGenerator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.util.ThreadContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Author: zc
 * Date: 2018/4/23
 * Description:
 */
@Service
@Slf4j
public class SaleServiceImpl implements SaleService {
    @Autowired
    private SaleOrderMapper saleOrderMapper;

    @Autowired
    private SaleOrderDetailMapper saleOrderDetailMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private OtherService otherService;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private AliPayUtil aliPayUtil;

    /**
     * ????????????
     */
    public static final int LOW_STOCK = 0;


    /**
     * @Author zc
     * @Date 2018/4/29 ??????11:01
     * @Param [storeId, saleOrderDto, pageNum, limit]
     * @Return com.ok.okhelper.common.PageModel<com.ok.okhelper.pojo.po.SaleOrder>
     * @Description:???????????????????????????????????? (?????????????????????)
     */
    @Override
    public PageModel<SaleOrderVo> getSaleOrderRecords(SaleOrderDto saleOrderDto, PageModel pageModel) {
        if(saleOrderDto.getStartDate().compareTo(saleOrderDto.getEndDate())>0){
            throw new IllegalException("??????????????????");
        }
        //????????????
        PageHelper.startPage(pageModel.getPageNum(), pageModel.getLimit());

        if("create_time desc".equals(pageModel.getOrderBy())){
            pageModel.setOrderBy("sale_order.create_time desc");
        }

        //????????????
        PageHelper.orderBy(pageModel.getOrderBy());

        List<SaleOrderVo> saleOrderVos = saleOrderMapper.getSaleOrderVo(JWTUtil.getStoreId(), saleOrderDto);


        if(CollectionUtils.isNotEmpty(saleOrderVos)){
            saleOrderVos.forEach(x->{
                x.setProductCount(x.getSaleOrderItemVos()!=null?x.getSaleOrderItemVos().size():0);
            });
        }

        PageInfo<SaleOrderVo> pageInfo = new PageInfo<>(saleOrderVos);
        PageModel<SaleOrderVo> dbPageModel = PageModel.convertToPageModel(pageInfo);

        //????????????????????????
        SaleTotalVo saleTotalVo =saleOrderMapper.getSaleTotal(JWTUtil.getStoreId(), saleOrderDto.getStartDate(), saleOrderDto.getEndDate());
        List<ProductCountMapVo> saleTotalProductCounts = saleOrderMapper.getSaleTotalProductCount(JWTUtil.getStoreId(), saleOrderDto.getStartDate(), saleOrderDto.getEndDate());
        if(CollectionUtils.isNotEmpty(saleTotalProductCounts)){
            saleTotalVo.setProductCountMap(saleTotalProductCounts);
            saleTotalVo.setTotalProductCount(saleTotalProductCounts.size());
            int sum = saleTotalProductCounts.stream().mapToInt(ProductCountMapVo::getSalesProductNum).sum();
            saleTotalVo.setTotalSalesProductNumber(sum);
        }
        dbPageModel.setTotalData(saleTotalVo);
        dbPageModel.setOrderBy(pageModel.getOrderBy());

        return dbPageModel;
    }

    /**
     * @Author zc  
     * @Date 2018/5/8 ??????8:35  
     * @Param [id]  
     * @Return com.ok.okhelper.pojo.vo.SaleOrderVo  
     * @Description:??????????????????
     */
    public SaleOrderVo getSaleOrderRecordOne(Long id){
        SaleOrderDto saleOrderDto=new SaleOrderDto();
        saleOrderDto.setId(id);
        List<SaleOrderVo> saleOrderVos = saleOrderMapper.getSaleOrderVo(JWTUtil.getStoreId(), saleOrderDto);

        if (CollectionUtils.isEmpty(saleOrderVos)||saleOrderVos.get(0)==null) {
            throw new NotFoundException("???????????????");
        }

        SaleOrderVo saleOrderVo=saleOrderVos.get(0);

        if (ObjectUtils.notEqual(saleOrderVo.getStoreId(), JWTUtil.getStoreId())) {
            throw new AuthorizationException("???????????????????????????????????????");
        }

        return saleOrderVo;
    }

    /**
     * @Author zc
     * @Date 2018/4/29 ??????11:00
     * @Param [storeId, seller, placeOrderDto]
     * @Return java.lang.String  ????????????vo
     * @Description:??????
     */
    @Override
    @Transactional
    public PlaceOrderVo placeOrder(Long storeId, Long seller, PlaceOrderDto placeOrderDto) {
        List<PlaceOrderItemDto> placeOrderItemDtos = placeOrderDto.getPlaceOrderItemDtos();

        //??????????????????
        SaleOrder saleOrder = new SaleOrder();
//      saleOrder.setToBePaid(toBePaid);

        BeanUtils.copyProperties(placeOrderDto, saleOrder);
        saleOrder.setOrderNumber(NumberGenerator.generatorOrderNumber(ConstStr.ODERTR_NUM_PREFIX_SALE, seller));
        saleOrder.setOrderStatus(ConstEnum.SALESTATUS_NOPAYMENT.getCode());
        saleOrder.setStoreId(storeId);
        saleOrder.setSeller(seller);
        saleOrder.setLogisticsStatus(ConstEnum.LOGISTICSSTATUS_NOSEND.getCode());

        saleOrderMapper.insertSelective(saleOrder);


        if (CollectionUtils.isNotEmpty(placeOrderItemDtos)) {
            //??????????????????
            assembleSaleOrderDetail(placeOrderItemDtos, saleOrder.getId());
            //???????????????
            otherService.checkAndCutStock(placeOrderItemDtos);
        }else {
            throw new IllegalException("???????????????");
        }


        //??????vo
        PlaceOrderVo placeOrderVo = new PlaceOrderVo();
        BeanUtils.copyProperties(saleOrder, placeOrderVo);

        return placeOrderVo;
    }


    /**
     * @Author zc
     * @Date 2018/4/30 ??????1:56
     * @Param [placeOrderItemDtos]
     * @Return void
     * @Description:??????????????????????????????????????????
     */
    public void assembleSaleOrderDetail(List<PlaceOrderItemDto> placeOrderItemDtos, Long saleOrderId) {
        placeOrderItemDtos.forEach(placeOrderItemDto -> {
            if(!ObjectUtils.allNotNull(placeOrderItemDto.getProductId(),placeOrderItemDto.getSaleCount(),placeOrderItemDto.getSalePrice())){
                throw new IllegalException("????????????????????????");
            }
            SaleOrderDetail saleOrderDetail = new SaleOrderDetail();
            Product product = productService.getProduct(placeOrderItemDto.getProductId());
            saleOrderDetail.setProductId(product.getId());
            saleOrderDetail.setMainImg(product.getMainImg());
            saleOrderDetail.setProductName(product.getProductName());
            saleOrderDetail.setProductTitle(product.getProductTitle());
            saleOrderDetail.setSaleOrderId(saleOrderId);
            saleOrderDetail.setSaleCount(placeOrderItemDto.getSaleCount());
            saleOrderDetail.setSalePrice(placeOrderItemDto.getSalePrice());
            placeOrderItemDto.setProductName(product.getProductName());
            try{
                int i = saleOrderDetailMapper.insertSelective(saleOrderDetail);
                if(i<=0){
                    throw new IllegalException("????????????:????????????????????????");
                }
            }catch (Exception e){
                throw new IllegalException("????????????:????????????????????????");
            }


        });
    }

    /**
     * @Author zc
     * @Date 2018/4/30 ??????1:53
     * @Param [placeOrderItemDtos]
     * @Return void
     * @Description:??????????????????
     */
    @Async
    public void recordHotSale(List<PlaceOrderItemDto> placeOrderItemDtos) {
        String zkey = ConstStr.HOT_SALE + ":" + JWTUtil.getStoreId() + ":" + DateFormatUtils.format(new Date(), "yyyyMMdd");
        placeOrderItemDtos.forEach(placeOrderItemDto -> {
            Long productId = placeOrderItemDto.getProductId();
            Integer salesCount = placeOrderItemDto.getSaleCount();
            redisTemplate.opsForZSet().incrementScore(zkey, String.valueOf(productId), salesCount);

            //???????????????zkey???????????????????????????????????????????????????30???
            if (!redisTemplate.hasKey(zkey)) {
                redisTemplate.expire(zkey, 30, TimeUnit.DAYS);
            }

        });
    }

    /**
     * @Author zc
     * @Date 2018/5/5 ??????10:44
     * @Param [customerId, sumPrice]
     * @Return void
     * @Description:??????????????????
     */
    @Async
    public void recordCustomerScore(Long customerId, BigDecimal sumPrice) {
        Customer dbcustomer = customerMapper.selectByPrimaryKey(customerId);

        if (dbcustomer == null) {
            throw new NotFoundException("???????????????");
        }
        if (ObjectUtils.notEqual(dbcustomer.getStoreId(), JWTUtil.getStoreId())) {
            throw new AuthorizationException("???????????????????????????????????????");
        }
        if (ConstEnum.STATUSENUM_UNAVAILABLE.getCode() == dbcustomer.getDeleteStatus()) {
            throw new IllegalException("?????????????????????");
        }

        Integer oldCustomerScore = dbcustomer.getCustomerScore();
        Integer newCustomer = oldCustomerScore + sumPrice.intValue();
        Customer customer = new Customer();
        customer.setCustomerScore(newCustomer);
        customer.setId(customerId);

        int i = customerMapper.updateByPrimaryKeySelective(customer);
        if (i <= 0) {
            throw new IllegalException("????????????????????????");
        }
    }

    /**
     * @Author zc
     * @Date 2018/5/3 ??????10:25
     * @Param [saleOrderId]
     * @Return void
     * @Description:????????????
     */
    public void confirmReceipt(Long saleOrderId) {
        SaleOrder saleOrder = saleOrderMapper.selectByPrimaryKey(saleOrderId);

        if (saleOrder == null) {
            throw new NotFoundException("???????????????");
        }
        if (ObjectUtils.notEqual(saleOrder.getStoreId(), JWTUtil.getStoreId())) {
            throw new AuthorizationException("???????????????????????????????????????");
        }
        if (ConstEnum.SALESTATUS_CLOSE.getCode() == saleOrder.getOrderStatus()) {
            throw new IllegalException("???????????????");
        }
        if (ConstEnum.LOGISTICSSTATUS_RECEIVED.getCode() == saleOrder.getLogisticsStatus()) {
            throw new IllegalException("??????????????????????????????????????????");
        }

        SaleOrder newSaleOrder = new SaleOrder();
        newSaleOrder.setId(saleOrder.getId());
        newSaleOrder.setLogisticsStatus(ConstEnum.LOGISTICSSTATUS_RECEIVED.getCode());
        newSaleOrder.setCloseTime(new Date());
        //??????????????????????????????????????????
        if (ConstEnum.SALESTATUS_PAID.getCode() == newSaleOrder.getOrderStatus()) {
            newSaleOrder.setOrderStatus(ConstEnum.SALESTATUS_SUCCESS.getCode());
            newSaleOrder.setSuccessTime(new Date());
        }

        saleOrderMapper.updateByPrimaryKeySelective(newSaleOrder);
    }

    /**
     * @Author zc
     * @Date 2018/5/5 ??????2:28
     * @Param [saleOrderId]
     * @Return void
     * @Description:????????????
     */
    @Transactional
    public void closeOrder(Long saleOrderId,boolean isTask) {
        SaleOrder saleOrder = saleOrderMapper.selectByPrimaryKey(saleOrderId);
        if (saleOrder == null) {
            throw new NotFoundException("???????????????");
        }
        if (!isTask&&ObjectUtils.notEqual(saleOrder.getStoreId(), JWTUtil.getStoreId())) {
            throw new AuthorizationException("???????????????????????????????????????");
        }
        ThreadContext.unbindSubject();
        if (ConstEnum.SALESTATUS_CLOSE.getCode() == saleOrder.getOrderStatus()) {
            throw new IllegalException("???????????????");
        }

        saleOrder.setOrderStatus(ConstEnum.SALESTATUS_CLOSE.getCode());
        saleOrder.setCloseTime(new Date());
        int i = saleOrderMapper.updateByPrimaryKeySelective(saleOrder);

        if (i <= 0) {
            throw new IllegalException("??????????????????");
        }

        //??????????????????
        SaleOrderDetail saleOrderDetail = new SaleOrderDetail();
        saleOrderDetail.setSaleOrderId(saleOrderId);
        List<SaleOrderDetail> saleOrderDetails = saleOrderDetailMapper.select(saleOrderDetail);
        if (CollectionUtils.isNotEmpty(saleOrderDetails)) {
            saleOrderDetails.forEach(dbsaleOrderDetail ->
                    productMapper.addSalesStock(dbsaleOrderDetail.getSaleCount(), dbsaleOrderDetail.getProductId()));
        }

        //????????????
        if (saleOrder.getCustomerId() != null) {
            Customer dbcustomer = customerMapper.selectByPrimaryKey(saleOrder.getCustomerId());
            if (dbcustomer != null) {
                Customer customer = new Customer();
                customer.setId(dbcustomer.getId());
                customer.setCustomerScore(dbcustomer.getCustomerScore() - saleOrder.getSumPrice().intValue());
                customerMapper.updateByPrimaryKeySelective(customer);
            }
        }

    }


    /**
     * @Author zc
     * @Date 2018/5/5 ??????5:24
     * @Param [saleOrderId, paymentDto]
     * @Return void
     * @Description:??????
     */
    public void payment(Long saleOrderId, PaymentDto paymentDto) {
        SaleOrder saleOrder = saleOrderMapper.selectByPrimaryKey(saleOrderId);
        if (saleOrder == null) {
            throw new NotFoundException("???????????????");
        }
        if (ObjectUtils.notEqual(saleOrder.getStoreId(), JWTUtil.getStoreId())) {
            throw new AuthorizationException("???????????????????????????????????????");
        }
        if (ConstEnum.SALESTATUS_CLOSE.getCode() == saleOrder.getOrderStatus()) {
            throw new IllegalException("???????????????");
        }
        if (ConstEnum.SALESTATUS_SUCCESS.getCode() == saleOrder.getOrderStatus()) {
            throw new IllegalException("???????????????");
        }
        if (ConstEnum.SALESTATUS_PAID.getCode() == saleOrder.getOrderStatus()) {
            throw new IllegalException("??????????????????????????????????????????");
        }
        if (saleOrder.getSumPrice().subtract(saleOrder.getRealPay()).subtract(paymentDto.getRealPay()).doubleValue()<0.0) {
            throw new IllegalException("????????????????????????????????????");
        }

        //?????????????????????(???????????????)
        String payNumber
                = NumberGenerator.generatorPayMentOrderNumber(saleOrderId, paymentDto.getTradeType(), paymentDto.getPayType());

        String aliPayTradeNumber=null;

        //???????????????
        if(String.valueOf(ConstEnum.PAYTYPE_ALIPAY.getCode()).equals(paymentDto.getPayType())){
            if(StringUtils.isBlank(paymentDto.getAliPayAuthCode())){
                throw new IllegalException("??????????????????????????????");
            }
            if(ConstEnum.TRADETYPE_FIRST.getCode()==paymentDto.getTradeType()){
                aliPayTradeNumber=aliPayUtil.alipay(payNumber,paymentDto.getAliPayAuthCode(),paymentDto.getRealPay().toString(),"0.00","OK???????????????-"+saleOrder.getOrderNumber());
            }else if(ConstEnum.TRADETYPE_REPAYMENT.getCode()==paymentDto.getTradeType()){
                aliPayTradeNumber=aliPayUtil.alipay(payNumber,paymentDto.getAliPayAuthCode(),paymentDto.getRealPay().toString(),"0.00","OK???????????????-"+saleOrder.getOrderNumber());
            }
        }


        //??????????????????

        //(????????????????????????+??????????????????)
        BigDecimal realPay = saleOrder.getRealPay().add(paymentDto.getRealPay());
        //????????????=????????????-(????????????????????????+??????????????????)
        BigDecimal toBePaid
                = saleOrder.getSumPrice().subtract(realPay);

        saleOrder.setRealPay(realPay);
        saleOrder.setToBePaid(toBePaid);
        saleOrder.setPayTime(new Date());

        //??????????????????????????????????????????
        if (toBePaid.doubleValue() > 0.0) {
            saleOrder.setOrderStatus(ConstEnum.SALESTATUS_DEBT.getCode());
        } else {
            saleOrder.setOrderStatus(ConstEnum.SALESTATUS_PAID.getCode());
            if (ConstEnum.LOGISTICSSTATUS_RECEIVED.getCode() == saleOrder.getLogisticsStatus()) {
                saleOrder.setOrderStatus(ConstEnum.SALESTATUS_SUCCESS.getCode());
            }
        }

        //??????????????????,??????
        try {
            ObjectMapper objectMapper=new ObjectMapper();
            Map<String,String> map = objectMapper.readValue(saleOrder.getPayType(), Map.class);
            String dbpayMentTypePrice = map.get(paymentDto.getPayType());
            if(dbpayMentTypePrice==null){
                throw new IllegalException("payType????????????");
            }
            //?????????????????????????????????
            BigDecimal dbpayMentTypePriceDecimal=new BigDecimal(dbpayMentTypePrice);
            BigDecimal newpayMentTypePriceDecimal = dbpayMentTypePriceDecimal.add(paymentDto.getRealPay());
            map.put(paymentDto.getPayType(),newpayMentTypePriceDecimal.toString());
            String newPayType = objectMapper.writeValueAsString(map);
            saleOrder.setPayType(newPayType);
        } catch (IOException e) {
           log.error("?????????{}",e.getMessage());
           //???????????????
           aliPayUtil.refund(saleOrderId.toString(),aliPayTradeNumber,realPay.toString());
           throw new IllegalException("???????????????????????????");
        }


        //???????????????
        int i = saleOrderMapper.updateByPrimaryKeySelective(saleOrder);
        if (i <= 0) {
            //???????????????
            aliPayUtil.refund(saleOrderId.toString(),aliPayTradeNumber,realPay.toString());
            throw new IllegalException("????????????");
        }



    }

}
