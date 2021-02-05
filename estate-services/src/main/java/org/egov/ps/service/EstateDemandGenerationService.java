package org.egov.ps.service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.egov.ps.config.Configuration;
import org.egov.ps.model.EstateDemandCriteria;
import org.egov.ps.model.PaymentConfig;
import org.egov.ps.model.PaymentConfigItems;
import org.egov.ps.model.Property;
import org.egov.ps.model.PropertyCriteria;
import org.egov.ps.producer.Producer;
import org.egov.ps.repository.PropertyRepository;
import org.egov.ps.service.calculation.IEstateRentCollectionService;
import org.egov.ps.util.PSConstants;
import org.egov.ps.web.contracts.AuditDetails;
import org.egov.ps.web.contracts.EstateAccount;
import org.egov.ps.web.contracts.EstateDemand;
import org.egov.ps.web.contracts.EstatePayment;
import org.egov.ps.web.contracts.PropertyRequest;
import org.joda.time.DateTimeComparator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.google.common.util.concurrent.AtomicDouble;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EstateDemandGenerationService {

	private PropertyRepository propertyRepository;
	private IEstateRentCollectionService estateRentCollectionService;
	private Configuration config;
	private Producer producer;
	private PropertyNotificationService propertyNotificationService;

	private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("dd/MM/yyyy");

	@Autowired
	public EstateDemandGenerationService(PropertyRepository propertyRepository, Producer producer, Configuration config,
			IEstateRentCollectionService estateRentCollectionService,
			PropertyNotificationService propertyNotificationService) {
		this.propertyRepository = propertyRepository;
		this.estateRentCollectionService = estateRentCollectionService;
		this.producer = producer;
		this.config = config;
		this.propertyNotificationService = propertyNotificationService;
	}

	public boolean checkSameDay(Date date1, Date date2) {
		Calendar cal1 = Calendar.getInstance();
		Calendar cal2 = Calendar.getInstance();
		cal1.setTime(date1);
		cal2.setTime(date2);
		return cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
				&& cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR);
	}

	public static boolean checkMonthWithCurrentDate(Date requestedDate) {
		Calendar cal1 = Calendar.getInstance();
		Calendar cal2 = Calendar.getInstance();
		cal1.setTime(new Date());
		cal2.setTime(requestedDate);
		if (cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
				&& cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH)) {
			return cal1.get(Calendar.DAY_OF_YEAR) >= cal2.get(Calendar.DAY_OF_YEAR);
		} else {
			return true;
		}
	}

	public static boolean compareDates(Date requestedDate, Date compareDate) {
		Calendar cal1 = Calendar.getInstance();
		Calendar cal2 = Calendar.getInstance();
		cal1.setTime(compareDate);
		cal2.setTime(requestedDate);
		if (cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
				&& cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH)) {
			return cal1.get(Calendar.DAY_OF_YEAR) >= cal2.get(Calendar.DAY_OF_YEAR);
		} else {
			return false;
		}
	}

	public AtomicInteger createMissingDemands(Property property) {
		AtomicInteger counter = new AtomicInteger(0);

		Boolean monthly = property.getPropertyDetails().getPaymentConfig().getGroundRentGenerationType()
				.equalsIgnoreCase(PSConstants.MONTHLY);

		/* Fetch billing date of the property */
		Date propertyBillingDate = new Date(
				property.getPropertyDetails().getPaymentConfig().getGroundRentBillStartDate());
		if (monthly)
			propertyBillingDate = getFirstDateOfMonth(
					new Date(property.getPropertyDetails().getPaymentConfig().getGroundRentBillStartDate()));

		/*
		 * fetch the consolidated demand, if consolidated demand present , take the date
		 * of consolidated demand and created demand from that date
		 */

		List<EstateDemand> demands = property.getPropertyDetails().getEstateDemands().stream()
				.filter(demand -> demand.getIsPrevious()).collect(Collectors.toList());
		Collections.sort(demands);
		if (demands.size() > 0) {
			for (EstateDemand demand : demands) {
				propertyBillingDate = new Date(demand.getGenerationDate());
			}
		}
		List<Date> allMonthDemandDatesTillCurrentMonth = new ArrayList<>();
		if (monthly)
			allMonthDemandDatesTillCurrentMonth = getAllRemainingDates(propertyBillingDate);
		else
			allMonthDemandDatesTillCurrentMonth = getAllRemainingDatesYear(propertyBillingDate);

		for (Date demandDate : allMonthDemandDatesTillCurrentMonth) {
			if (monthly)
				demandDate = setDateOfMonth(demandDate, Integer.parseInt(
						property.getPropertyDetails().getPaymentConfig().getGroundRentGenerateDemand().toString()));
			Date demandGenerationStartDate = demandDate;
			/* Here checking demand date is already created or not */
			List<EstateDemand> inRequestDemands = property.getPropertyDetails().getEstateDemands().stream()
					.filter(demand -> checkSameDay(new Date(demand.getGenerationDate()), demandGenerationStartDate))
					.collect(Collectors.toList());
			if (inRequestDemands.isEmpty() && checkMonthWithCurrentDate(demandGenerationStartDate)
					&& property.getPropertyDetails().getPropertyType().equalsIgnoreCase(PSConstants.ES_PM_LEASEHOLD)) {
				// generate demand
				counter.getAndIncrement();
				generateEstateDemand(property, getFirstDateOfMonth(demandGenerationStartDate));
			}
		}
		return counter;
	}

	public AtomicInteger createDemand(EstateDemandCriteria demandCriteria) {
		List<String> relations = new ArrayList<String>();
		relations.add("owner");
		relations.add("paymentconfig");
		AtomicInteger counter = new AtomicInteger(0);
		PropertyCriteria propertyCriteria = new PropertyCriteria();
		propertyCriteria.setRelations(relations);
		propertyCriteria.setState(Arrays.asList(PSConstants.PM_APPROVED));
		List<Property> propertyList = propertyRepository.getProperties(propertyCriteria);

		propertyList.forEach(property -> {
			try {
				propertyCriteria.setPropertyId(property.getId());
				List<String> propertyDetailsId = Arrays.asList(property.getPropertyDetails().getId());
				List<EstateDemand> estateDemandList = propertyRepository
						.getPropertyDetailsEstateDemandDetails(propertyDetailsId);
				property.getPropertyDetails().setEstateDemands(estateDemandList);
				if (!CollectionUtils.isEmpty(estateDemandList)) {
					List<EstatePayment> estatePaymentList = propertyRepository
							.getPropertyDetailsEstatePaymentDetails(propertyDetailsId);
					property.getPropertyDetails().setEstatePayments(estatePaymentList);
					EstateAccount estateAccount = propertyRepository
							.getAccountDetailsForPropertyDetailsIds(propertyDetailsId);
					property.getPropertyDetails().setEstateAccount(estateAccount);

					Date date = demandCriteria.isEmpty() ? new Date() : FORMATTER.parse(demandCriteria.getDate());
					Date generateDemandDate = getFirstDateOfMonth(new Date());

					List<EstateDemand> existingDemands = estateDemandList.stream().filter(demand -> DateTimeComparator
							.getDateOnlyInstance().compare(demand.getGenerationDate(), date) == 0)
							.collect(Collectors.toList());

					if (property.getPropertyDetails().getPaymentConfig() != null && property.getPropertyDetails()
							.getPropertyType().equalsIgnoreCase(PSConstants.ES_PM_LEASEHOLD)) {
						PaymentConfig paymentConfig = property.getPropertyDetails().getPaymentConfig();
						if (paymentConfig.getGroundRentGenerationType().equalsIgnoreCase(PSConstants.MONTHLY)) {
							generateDemandDate = setDateOfMonth(date,
									Integer.parseInt(paymentConfig.getGroundRentGenerateDemand().toString()));
						} else if (paymentConfig.getGroundRentGenerationType().equalsIgnoreCase(PSConstants.ANNUALLY)) {
							generateDemandDate = new Date(paymentConfig.getGroundRentBillStartDate());
						}
					}

					if (existingDemands.isEmpty() && compareDates(generateDemandDate, date) && property
							.getPropertyDetails().getPropertyType().equalsIgnoreCase(PSConstants.ES_PM_LEASEHOLD)) {
						// generate demand
						counter.getAndIncrement();
						generateEstateDemand(property, getFirstDateOfMonth(date), estateDemandList, estatePaymentList,
								estateAccount);
					}
				} else {
					log.debug("We are skipping generating estate demands for this property id: " + property.getId()
							+ " as there is no estate history");
				}

			} catch (Exception e) {
				log.error("exception occured for property id: " + property.getId());
			}
		});

		return counter;
	}

	private void generateEstateDemand(Property property, Date date, List<EstateDemand> estateDemandList,
			List<EstatePayment> estatePaymentList, EstateAccount estateAccount) {

		Double calculatedRent = calculateRentAccordingtoMonth(property, date);
		if (property.getPropertyDetails().getPaymentConfig() != null
				&& property.getPropertyDetails().getPropertyType().equalsIgnoreCase(PSConstants.ES_PM_LEASEHOLD)) {
			PaymentConfig paymentConfig = property.getPropertyDetails().getPaymentConfig();
			if (paymentConfig.getGroundRentGenerationType().equalsIgnoreCase(PSConstants.MONTHLY)) {
				date = setDateOfMonth(date, Integer.parseInt(paymentConfig.getGroundRentGenerateDemand().toString()));
			} else if (paymentConfig.getGroundRentGenerationType().equalsIgnoreCase(PSConstants.ANNUALLY)) {
				date = setDateOfMonth(date, new Date(paymentConfig.getGroundRentBillStartDate()).getDate());
			}
		}

		AuditDetails auditDetails = AuditDetails.builder().createdBy("System").createdTime(new Date().getTime())
				.lastModifiedBy("System").lastModifiedTime(new Date().getTime()).build();

		EstateDemand estateDemand = EstateDemand.builder().id(UUID.randomUUID().toString())
				.propertyDetailsId(property.getPropertyDetails().getId()).generationDate(date.getTime())
				.collectionPrincipal(0.0).remainingPrincipal(calculatedRent).interestSince(date.getTime())
				.isPrevious(false).rent(calculatedRent).penaltyInterest(0.0).gstInterest(0.0)
				.gst(calculatedRent * 18 / 100).noOfDays(0.0).paid(0.0).remainingRent(calculatedRent)
				.remainingGST(calculatedRent * 18 / 100).remainingRentPenalty(0.0).remainingGSTPenalty(0.0)
				.auditDetails(auditDetails).build();

		property.getPropertyDetails().getEstateDemands().add(estateDemand);

		log.info("Generating Estate demand id '{}' of principal '{}' for property with file no {}",
				estateDemand.getId(), property.getFileNumber());

		if (!CollectionUtils.isEmpty(property.getPropertyDetails().getEstatePayments())
				&& property.getPropertyDetails().getEstateAccount() != null
				&& property.getPropertyDetails().getPaymentConfig() != null
				&& property.getPropertyDetails().getPropertyType().equalsIgnoreCase(PSConstants.ES_PM_LEASEHOLD)) {

			// Replace the 4th line 18 with the MDMS data
			property.getPropertyDetails().setEstateRentCollections(estateRentCollectionService.settle(
					property.getPropertyDetails().getEstateDemands(), property.getPropertyDetails().getEstatePayments(),
					property.getPropertyDetails().getEstateAccount(), PSConstants.GST_INTEREST_RATE,
					property.getPropertyDetails().getPaymentConfig().getIsIntrestApplicable(),
					property.getPropertyDetails().getPaymentConfig().getRateOfInterest().doubleValue()));

		}
		double securityAmount = property.getPropertyDetails().getPaymentConfig().getNoOfMonths() * calculatedRent;
		property.getPropertyDetails().getPaymentConfig().setSecurityAmount(new BigDecimal(securityAmount));
		PropertyRequest propertyRequest = new PropertyRequest();
		propertyRequest.setProperties(Collections.singletonList(property));
		if (!CollectionUtils.isEmpty(property.getPropertyDetails().getEstateRentCollections())) {
			property.getPropertyDetails().getEstateRentCollections().forEach(collection -> {
				if (collection.getId() == null) {
					collection.setId(UUID.randomUUID().toString());
					collection.setAuditDetails(auditDetails);
				}

			});
		}
		producer.push(config.getUpdatePropertyTopic(), propertyRequest);
		propertyNotificationService.processDemandNotification(propertyRequest, estateDemand);
	}

	private Double calculateRentAccordingtoMonth(Property property, Date requestedDate) {
		PaymentConfig paymentConfig = property.getPropertyDetails().getPaymentConfig();
		AtomicInteger checkLoopIf = new AtomicInteger();
		if (paymentConfig != null
				&& property.getPropertyDetails().getPropertyType().equalsIgnoreCase(PSConstants.ES_PM_LEASEHOLD)) {
			Date startDate = new Date(paymentConfig.getGroundRentBillStartDate());
			String startDateText = new SimpleDateFormat("yyyy-MM-dd").format(startDate);
			String endDateText = new SimpleDateFormat("yyyy-MM-dd").format(requestedDate);

			/* Check Months between both date */
			long monthsBetween = ChronoUnit.MONTHS.between(LocalDate.parse(startDateText).withDayOfMonth(1),
					LocalDate.parse(endDateText).withDayOfMonth(1));

			for (PaymentConfigItems paymentConfigItem : paymentConfig.getPaymentConfigItems()) {
				if (paymentConfigItem.getGroundRentStartMonth() <= monthsBetween
						&& monthsBetween <= paymentConfigItem.getGroundRentEndMonth()) {
					checkLoopIf.incrementAndGet();
					return paymentConfigItem.getGroundRentAmount().doubleValue();
				}
			}
			if (checkLoopIf.get() == 0) {
				int paymentConfigCount = paymentConfig.getPaymentConfigItems().size() - 1;
				return paymentConfig.getPaymentConfigItems().get(paymentConfigCount).getGroundRentAmount()
						.doubleValue();
			}
		}
		return 0.0;
	}

	private BigDecimal calculateSecurityDeposit(Property property, Date requestedDate) {
		PaymentConfig paymentConfig = property.getPropertyDetails().getPaymentConfig();
		AtomicInteger checkLoopIf = new AtomicInteger();
		if (paymentConfig != null
				&& property.getPropertyDetails().getPropertyType().equalsIgnoreCase(PSConstants.ES_PM_LEASEHOLD)) {
			Date startDate = new Date(paymentConfig.getGroundRentBillStartDate());
			String startDateText = new SimpleDateFormat("yyyy-MM-dd").format(startDate);
			String endDateText = new SimpleDateFormat("yyyy-MM-dd").format(requestedDate);

			/* Check Months between both date */
			long monthsBetween = ChronoUnit.MONTHS.between(LocalDate.parse(startDateText).withDayOfMonth(1),
					LocalDate.parse(endDateText).withDayOfMonth(1));

			for (PaymentConfigItems paymentConfigItem : paymentConfig.getPaymentConfigItems()) {
				if (paymentConfigItem.getGroundRentStartMonth() <= monthsBetween
						&& monthsBetween <= paymentConfigItem.getGroundRentEndMonth()) {
					checkLoopIf.incrementAndGet();
					double securityDeposit = paymentConfigItem.getGroundRentAmount().doubleValue() * paymentConfig.getNoOfMonths();
					return new BigDecimal(securityDeposit);
				}
			}
			if (checkLoopIf.get() == 0) {
				int paymentConfigCount = paymentConfig.getPaymentConfigItems().size() - 1;
				double securityDeposit = paymentConfig.getPaymentConfigItems().get(paymentConfigCount).getGroundRentAmount()
						.doubleValue() * paymentConfig.getNoOfMonths();
				return new BigDecimal(securityDeposit);
			}
		}
		return new BigDecimal(0.0);
	}

	private Date setDateOfMonth(Date date, int value) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		cal.set(Calendar.DAY_OF_MONTH, value);
		cal.set(Calendar.SECOND, cal.getActualMinimum(Calendar.SECOND));
		cal.set(Calendar.MILLISECOND, cal.getActualMinimum(Calendar.MILLISECOND));
		cal.set(Calendar.MINUTE, cal.getActualMinimum(Calendar.MINUTE));
		cal.set(Calendar.HOUR_OF_DAY, cal.getActualMinimum(Calendar.HOUR_OF_DAY));
		return cal.getTime();
	}

	private Date getFirstDateOfMonth(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		cal.set(Calendar.DAY_OF_MONTH, cal.getActualMinimum(Calendar.DAY_OF_MONTH));
		cal.set(Calendar.SECOND, cal.getActualMinimum(Calendar.SECOND));
		cal.set(Calendar.MILLISECOND, cal.getActualMinimum(Calendar.MILLISECOND));
		cal.set(Calendar.MINUTE, cal.getActualMinimum(Calendar.MINUTE));
		cal.set(Calendar.HOUR_OF_DAY, cal.getActualMinimum(Calendar.HOUR_OF_DAY));
		return cal.getTime();
	}

	private List<Date> getAllRemainingDates(Date propertyBillingDate) {
		List<Date> allMonthDemandDatesTillCurrentMonth = new ArrayList<>();
		Calendar beginCalendar = Calendar.getInstance();
		Calendar finishCalendar = Calendar.getInstance();

		beginCalendar.setTime(propertyBillingDate);
		finishCalendar.setTime(new Date());

		while (beginCalendar.before(finishCalendar)) {
			// add one month to date per loop
			allMonthDemandDatesTillCurrentMonth.add(beginCalendar.getTime());
			beginCalendar.add(Calendar.MONTH, 1);
		}
		return allMonthDemandDatesTillCurrentMonth;
	}

	private static List<Date> getAllRemainingDatesYear(Date propertyBillingDate) {
		List<Date> allMonthDemandDatesTillCurrentMonth = new ArrayList<>();
		Calendar beginCalendar = Calendar.getInstance();
		Calendar finishCalendar = Calendar.getInstance();

		beginCalendar.setTime(propertyBillingDate);
		finishCalendar.setTime(new Date());

		while (beginCalendar.before(finishCalendar)) {
			// add one month to date per loop
			allMonthDemandDatesTillCurrentMonth.add(beginCalendar.getTime());
			beginCalendar.add(Calendar.YEAR, 1);
		}
		return allMonthDemandDatesTillCurrentMonth;
	}

	private void generateEstateDemand(Property property, Date date) {
		Double calculatedRent = calculateRentAccordingtoMonth(property, date);
		if (property.getPropertyDetails().getPaymentConfig() != null
				&& property.getPropertyDetails().getPropertyType().equalsIgnoreCase(PSConstants.ES_PM_LEASEHOLD)) {
			PaymentConfig paymentConfig = property.getPropertyDetails().getPaymentConfig();
			if (paymentConfig.getGroundRentGenerationType().equalsIgnoreCase(PSConstants.MONTHLY)) {
				date = setDateOfMonth(date, Integer.parseInt(paymentConfig.getGroundRentGenerateDemand().toString()));
			} else if (paymentConfig.getGroundRentGenerationType().equalsIgnoreCase(PSConstants.ANNUALLY)) {
				date = setDateOfMonth(date, new Date(paymentConfig.getGroundRentBillStartDate()).getDate());
			}
		}

		EstateDemand estateDemand = EstateDemand.builder().generationDate(date.getTime()).collectionPrincipal(0.0)
				.remainingPrincipal(calculatedRent).interestSince(date.getTime()).isPrevious(false).rent(calculatedRent)
				.penaltyInterest(0.0).gstInterest(0.0).gst(calculatedRent * 18 / 100).noOfDays(0.0).paid(0.0)
				.remainingRent(calculatedRent).remainingGST(calculatedRent * 18 / 100).remainingRentPenalty(0.0)
				.remainingGSTPenalty(0.0).build();

		double securityAmount = property.getPropertyDetails().getPaymentConfig().getNoOfMonths() * calculatedRent;
		property.getPropertyDetails().getPaymentConfig().setSecurityAmount(new BigDecimal(securityAmount));
		property.getPropertyDetails().getEstateDemands().add(estateDemand);

		log.info("Generating Estate demand id '{}' of principal '{}' for property with file no {}",
				estateDemand.getId(), property.getFileNumber());

	}

	public void bifurcateDemand(Property property) {
		boolean hasAnyNewEstateDemands = property.getPropertyDetails().getEstateDemands().stream()
				.filter(estateDemand -> estateDemand.getId() == null || estateDemand.getId().isEmpty()).findAny()
				.isPresent();
		if (hasAnyNewEstateDemands) {

			AtomicDouble demandRent = new AtomicDouble(0);
			List<EstateDemand> newDemands = new ArrayList<>();
			property.getPropertyDetails().getEstateDemands().forEach(estateDemand -> {
				double GSTinterest = 0D;
				double rentPenaltyInterest = 0D;

				if (estateDemand.getId() == null && estateDemand.getIsPrevious()) {
					Calendar consolidateDemandCal = Calendar.getInstance();
					consolidateDemandCal.setTime(new Date(estateDemand.getGenerationDate()));
					long consolidateDemandDay = consolidateDemandCal.get(Calendar.DATE);
					long consolidateDemandMonth = consolidateDemandCal.get(Calendar.MONTH) + 1;
					long consolidateDemandYear = consolidateDemandCal.get(Calendar.YEAR);

					if (consolidateDemandDay <= property.getPropertyDetails().getPaymentConfig()

							.getGroundRentGenerateDemand()) {
// rent=1000 consolidateRent=3500
						Calendar prevDemandDateCal = Calendar.getInstance();
						prevDemandDateCal.setTime(new Date());
						prevDemandDateCal.set(Calendar.DATE, property.getPropertyDetails().getPaymentConfig()
								.getGroundRentGenerateDemand().intValue());
						prevDemandDateCal.set(Calendar.MONTH,
								consolidateDemandMonth == 1 ? 12 : (int) consolidateDemandMonth - 2);
						prevDemandDateCal.set(Calendar.YEAR,
								consolidateDemandMonth == 1 ? (int) consolidateDemandYear - 1
										: (int) consolidateDemandYear);
						Date prevDemandDate = prevDemandDateCal.getTime();

						demandRent.set(calculateRentAccordingtoMonth(property, prevDemandDate));

						property.getPropertyDetails().getPaymentConfig()
								.setSecurityAmount(calculateSecurityDeposit(property, prevDemandDate));

						if (demandRent.get() < estateDemand.getRent()) {

							EstateDemand prevDemand = new EstateDemand();
							prevDemand.setRent(demandRent.get());
							prevDemand.setGst(demandRent.get() * 0.18);
							prevDemand.setPenaltyInterest(0D);
							prevDemand.setGstInterest(0D);

							prevDemand.setGenerationDate(prevDemandDate.getTime());
							prevDemand.setBifurcate(true);

							LocalDate prevDemandDateLocal = getLocalDate(prevDemandDate.getTime());
							LocalDate estateDemandLocal = getLocalDate(estateDemand.getGenerationDate());
							long noOfDaysForInterestCalculation = ChronoUnit.DAYS.between(prevDemandDateLocal,
									estateDemandLocal);

							if (noOfDaysForInterestCalculation > estateDemand.getInitialGracePeriod()) {

								GSTinterest = prevDemand.getGst() * .18 * noOfDaysForInterestCalculation / 365;

								if (property.getPropertyDetails().getPaymentConfig().getIsIntrestApplicable()) {
									rentPenaltyInterest = prevDemand.getRent() * property.getPropertyDetails()
											.getPaymentConfig().getRateOfInterest().doubleValue() / 100;
								} else {
									rentPenaltyInterest = prevDemand.getRent() * (property.getPropertyDetails()
											.getPaymentConfig().getRateOfInterest().doubleValue() / 100)
											* noOfDaysForInterestCalculation / 365;
								}
								prevDemand.setPenaltyInterest(rentPenaltyInterest);
								prevDemand.setGstInterest(GSTinterest);
							}
							prevDemand.setInterestSince(estateDemand.getGenerationDate());
							prevDemand.setAdjustmentDate(estateDemand.getGenerationDate());
							estateDemand.setRent(estateDemand.getRent() - prevDemand.getRent());
							estateDemand.setGst(estateDemand.getGst() - prevDemand.getGst());
							estateDemand.setPenaltyInterest(
									estateDemand.getPenaltyInterest() - prevDemand.getPenaltyInterest());
							estateDemand.setGstInterest(estateDemand.getGstInterest() - prevDemand.getGstInterest());

							newDemands.add(prevDemand);

						} else if (demandRent.get() > estateDemand.getRent()) {

							estateDemand.setIsPrevious(false);
							estateDemand.setGenerationDate(prevDemandDate.getTime());
							estateDemand.setBifurcate(true);
							estateDemand.setInterestSince(estateDemand.getGenerationDate());
							estateDemand.setAdjustmentDate(estateDemand.getGenerationDate());
						}

					}

					/**
					 * Consolidated date is greater than demand generation date and consolidated
					 * demand rent amount is more than demand rent
					 */
					else if (consolidateDemandDay > property.getPropertyDetails().getPaymentConfig()
							.getGroundRentGenerateDemand()) {

						Calendar prevDemandDateCal = Calendar.getInstance();
						prevDemandDateCal.setTime(new Date());
						prevDemandDateCal.set(Calendar.DATE, property.getPropertyDetails().getPaymentConfig()
								.getGroundRentGenerateDemand().intValue());
						prevDemandDateCal.set(Calendar.MONTH, (int) consolidateDemandMonth - 1);
						prevDemandDateCal.set(Calendar.YEAR, (int) consolidateDemandYear);
						Date prevDemandDate = prevDemandDateCal.getTime();

						demandRent.set(calculateRentAccordingtoMonth(property, prevDemandDate));

						property.getPropertyDetails().getPaymentConfig()
								.setSecurityAmount(calculateSecurityDeposit(property, prevDemandDate));
						/**
						 * consolidated demand rent amount is more than demand rent
						 */
						if (demandRent.get() < estateDemand.getRent()) {

							EstateDemand prevDemand = new EstateDemand();
							prevDemand.setRent(demandRent.get());
							prevDemand.setGst(demandRent.get() * 0.18);
							prevDemand.setPenaltyInterest(0D);
							prevDemand.setGstInterest(0D);

							prevDemand.setGenerationDate(prevDemandDate.getTime());
							prevDemand.setBifurcate(true);
							LocalDate prevDemandDateLocal = getLocalDate(prevDemandDate.getTime());
							LocalDate estateDemandLocal = getLocalDate(estateDemand.getGenerationDate());

							long noOfDaysForInterestCalculation = ChronoUnit.DAYS.between(prevDemandDateLocal,
									estateDemandLocal);
							if (noOfDaysForInterestCalculation > estateDemand.getInitialGracePeriod()) {

								GSTinterest = prevDemand.getGst() * .18 * noOfDaysForInterestCalculation / 365;

								if (property.getPropertyDetails().getPaymentConfig().getIsIntrestApplicable()) {
									GSTinterest = prevDemand.getRent() * property.getPropertyDetails()
											.getPaymentConfig().getRateOfInterest().doubleValue() / 100;
								} else {
									rentPenaltyInterest = prevDemand.getRent() * (property.getPropertyDetails()
											.getPaymentConfig().getRateOfInterest().doubleValue() / 100)
											* noOfDaysForInterestCalculation / 365;
								}
								prevDemand.setPenaltyInterest(rentPenaltyInterest);
								prevDemand.setGstInterest(GSTinterest);
							}
							prevDemand.setInterestSince(estateDemand.getGenerationDate());
							prevDemand.setAdjustmentDate(estateDemand.getGenerationDate());
							estateDemand.setRent(estateDemand.getRent() - prevDemand.getRent());
							estateDemand.setGst(estateDemand.getGst() - prevDemand.getGst());
							estateDemand.setPenaltyInterest(
									estateDemand.getPenaltyInterest() - prevDemand.getPenaltyInterest());
							estateDemand.setGstInterest(estateDemand.getGstInterest() - prevDemand.getGstInterest());

							newDemands.add(prevDemand);
						} else if (demandRent.get() > estateDemand.getRent()) {

							estateDemand.setIsPrevious(false);
							estateDemand.setGenerationDate(prevDemandDate.getTime());
							estateDemand.setBifurcate(true);
							estateDemand.setInterestSince(estateDemand.getGenerationDate());
							estateDemand.setAdjustmentDate(estateDemand.getGenerationDate());
						}
					}

				}
			});
			property.getPropertyDetails().getEstateDemands().addAll(newDemands);
		}

	}

	private LocalDate getLocalDate(long atTimestamp) {
		return Instant.ofEpochMilli(atTimestamp).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	public void addCredit(Property property) {
		/*
		 * Payment config advance rent amount insert in estate-account remaining amount
		 */
		if (property.getPropertyDetails().getPaymentConfig().getGroundRentAdvanceRent() != null) {
			Double currentRemainingAmount = property.getPropertyDetails().getEstateAccount().getRemainingAmount();
			property.getPropertyDetails().getEstateAccount().setRemainingAmount(currentRemainingAmount
					+ property.getPropertyDetails().getPaymentConfig().getGroundRentAdvanceRent().doubleValue());
			property.getPropertyDetails().getEstateAccount()
					.setRemainingSince(property.getPropertyDetails().getPaymentConfig().getGroundRentAdvanceRentDate());
		}
	}

}