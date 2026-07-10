(function () {
  "use strict";

  var form = document.getElementById("registration-form");
  var nextInput = document.getElementById("next-url");
  var registrantSection = document.getElementById("registrant-section");
  var patientTypeSection = document.getElementById("patient-type-section");
  var relationshipFields = document.getElementById("relationship-fields");
  var relationshipSelect = document.getElementById("relationship");
  var relationshipOtherWrap = document.getElementById("relationship-other-wrap");
  var relationshipOtherInput = document.getElementById("relationship-other");
  var radios = form.querySelectorAll('input[name="registrant"]');

  var API_BASE = window.DELTAV_API_URL || "";
  var practiceId = new URLSearchParams(window.location.search).get("practice") || "";

  // ── URL helpers ──────────────────────────────────────────────────────────
  function getNextUrl() {
    var params = new URLSearchParams(window.location.search);
    var next = params.get("next") || params.get("redirect") || "";
    try { return next ? decodeURIComponent(next) : ""; } catch (e) { return ""; }
  }

  // ── Prefill from URL params ───────────────────────────────────────────────
  function prefillFromParams() {
    var p = new URLSearchParams(window.location.search);
    function get(keys) {
      for (var i = 0; i < keys.length; i++) {
        var v = p.get(keys[i]);
        if (v) return decodeURIComponent(v);
      }
      return "";
    }

    var firstName  = get(["first_name",  "firstname",  "firstName"]);
    var lastName   = get(["last_name",   "lastname",   "lastName"]);
    var middleName = get(["middle_name", "middlename", "middleName"]);
    var dob        = get(["dob", "date_of_birth", "dateofbirth"]);

    function fill(id, val) {
      if (!val) return;
      var el = document.getElementById(id);
      if (!el) return;
      el.value = val;
      el.classList.add("prefilled");
    }

    fill("first-name",  firstName);
    fill("middle-name", middleName);
    fill("last-name",   lastName);

    // DOB may arrive as YYYY-MM-DD (ISO) — convert to MM/DD/YYYY for the text input
    if (dob) {
      var dobEl = document.getElementById("dob");
      var isoMatch = dob.match(/^(\d{4})-(\d{2})-(\d{2})$/);
      dobEl.value = isoMatch ? isoMatch[2] + "/" + isoMatch[3] + "/" + isoMatch[1] : dob;
      dobEl.classList.add("prefilled");
    }

    // If patient name is supplied → existing patient; hide both toggle sections
    if (firstName && lastName) {
      if (registrantSection) registrantSection.hidden = true;
      if (patientTypeSection) patientTypeSection.hidden = true;
      var existingRadio = form.querySelector('input[name="patient_type"][value="existing"]');
      if (existingRadio) existingRadio.checked = true;
    }
  }

  // ── Relationship fields ───────────────────────────────────────────────────
  function showRelationshipFields(show) {
    relationshipFields.hidden = !show;
    if (!show) {
      relationshipSelect.removeAttribute("required");
      relationshipOtherInput.removeAttribute("required");
    } else {
      relationshipSelect.setAttribute("required", "required");
      toggleRelationshipOther();
    }
  }

  function toggleRelationshipOther() {
    var isOther = relationshipSelect.value === "other";
    relationshipOtherWrap.hidden = !isOther;
    if (isOther) {
      relationshipOtherInput.setAttribute("required", "required");
    } else {
      relationshipOtherInput.removeAttribute("required");
      relationshipOtherInput.value = "";
    }
  }

  function toggleFromRegistrant() {
    var checked = form.querySelector('input[name="registrant"]:checked');
    showRelationshipFields(checked && checked.value === "another");
  }

  radios.forEach(function (r) { r.addEventListener("change", toggleFromRegistrant); });
  relationshipSelect.addEventListener("change", toggleRelationshipOther);

  // ── DOB auto-format MM/DD/YYYY ────────────────────────────────────────────
  var dobInput = document.getElementById("dob");
  if (dobInput) {
    dobInput.addEventListener("input", function () {
      var val = dobInput.value.replace(/[^\d]/g, "");
      if (val.length >= 5) {
        dobInput.value = val.slice(0, 2) + "/" + val.slice(2, 4) + "/" + val.slice(4, 8);
      } else if (val.length >= 3) {
        dobInput.value = val.slice(0, 2) + "/" + val.slice(2);
      }
    });
  }

  function parseDob(str) {
    var parts = str.split("/");
    if (parts.length !== 3) return null;
    var mm = parseInt(parts[0], 10), dd = parseInt(parts[1], 10), yyyy = parseInt(parts[2], 10);
    if (!mm || !dd || !yyyy || mm < 1 || mm > 12 || dd < 1 || dd > 31 || yyyy < 1900) return null;
    var date = new Date(yyyy, mm - 1, dd);
    if (date.getMonth() !== mm - 1 || date.getDate() !== dd) return null;
    return date;
  }

  function dobToIso(str) {
    var parts = str.split("/");
    return parts[2] + "-" + parts[0] + "-" + parts[1];
  }

  // ── Calendar & slot picker ────────────────────────────────────────────────
  var calYear, calMonth, selectedDay;
  var selectedSlots = [];
  var MAX_SLOTS = 3;

  var MONTH_NAMES = ["January","February","March","April","May","June","July","August","September","October","November","December"];
  var DAY_ABBR    = ["Su","Mo","Tu","We","Th","Fr","Sa"];
  var DAY_SHORT   = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
  var MON_SHORT   = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];

  var TIME_SLOTS = ["AM", "PM"];

  function padZ(n) { return String(n).padStart(2, "0"); }
  function toDateStr(y, m, d) { return y + "-" + padZ(m) + "-" + padZ(d); }

  function dateStrDisplay(str) {
    var p = str.split("-");
    var d = new Date(parseInt(p[0]), parseInt(p[1]) - 1, parseInt(p[2]));
    return DAY_SHORT[d.getDay()] + ", " + MON_SHORT[d.getMonth()] + " " + d.getDate();
  }

  function findSlotIndex(key) {
    for (var i = 0; i < selectedSlots.length; i++) {
      if (selectedSlots[i].key === key) return i;
    }
    return -1;
  }

  function hasDateSlots(dateStr) {
    for (var i = 0; i < selectedSlots.length; i++) {
      if (selectedSlots[i].date === dateStr) return true;
    }
    return false;
  }

  function renderCalendar() {
    document.getElementById("cal-month-label").textContent = MONTH_NAMES[calMonth] + " " + calYear;
    var grid = document.getElementById("cal-grid");
    grid.innerHTML = "";

    DAY_ABBR.forEach(function (d) {
      var h = document.createElement("div");
      h.className = "cal-day-name";
      h.textContent = d;
      grid.appendChild(h);
    });

    var firstWeekday = new Date(calYear, calMonth, 1).getDay();
    var daysInMonth  = new Date(calYear, calMonth + 1, 0).getDate();
    var today = new Date(); today.setHours(0, 0, 0, 0);
    var maxDate = new Date(today); maxDate.setDate(maxDate.getDate() + 90);

    var nextMonthStart = new Date(calYear, calMonth + 1, 1);
    document.getElementById("cal-next").disabled = nextMonthStart > maxDate;

    for (var e = 0; e < firstWeekday; e++) {
      var empty = document.createElement("div");
      empty.className = "cal-cell empty";
      grid.appendChild(empty);
    }

    for (var day = 1; day <= daysInMonth; day++) {
      var cellDate = new Date(calYear, calMonth, day);
      var dateStr  = toDateStr(calYear, calMonth + 1, day);
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "cal-cell";
      btn.textContent = day;

      if (cellDate < today || cellDate > maxDate) {
        btn.disabled = true;
        btn.classList.add("past");
      } else {
        if (cellDate.getTime() === today.getTime()) btn.classList.add("today");
        if (dateStr === selectedDay) btn.classList.add("active");
        if (hasDateSlots(dateStr)) btn.classList.add("has-slots");
        btn.addEventListener("click", (function (ds) {
          return function () { selectDay(ds); };
        })(dateStr));
      }
      grid.appendChild(btn);
    }
  }

  function selectDay(dateStr) {
    selectedDay = dateStr;
    renderCalendar();
    renderTimeSlots(dateStr);
  }

  function renderTimeSlots(dateStr) {
    var wrap  = document.getElementById("cal-slots-wrap");
    var label = document.getElementById("cal-slots-date");
    var cont  = document.getElementById("cal-slots");

    label.textContent = dateStrDisplay(dateStr);
    cont.innerHTML = "";
    wrap.hidden = false;

    var atMax = selectedSlots.length >= MAX_SLOTS;

    TIME_SLOTS.forEach(function (time) {
      var key = dateStr + "|" + time;
      var isSelected = findSlotIndex(key) !== -1;
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "slot-btn" + (isSelected ? " selected" : "");
      btn.textContent = time;
      if (!isSelected && atMax) { btn.disabled = true; }
      btn.addEventListener("click", function () { toggleSlot(dateStr, time, key); });
      cont.appendChild(btn);
    });
  }

  function toggleSlot(dateStr, time, key) {
    var idx = findSlotIndex(key);
    if (idx !== -1) {
      selectedSlots.splice(idx, 1);
    } else if (selectedSlots.length < MAX_SLOTS) {
      selectedSlots.push({ date: dateStr, time: time, key: key });
    }
    renderTimeSlots(dateStr);
    renderCalendar();
    renderSelectedSlots();
    updateSubmitState();
  }

  function renderSelectedSlots() {
    var wrap  = document.getElementById("selected-slots");
    var chips = document.getElementById("slot-chips");
    if (selectedSlots.length === 0) { wrap.hidden = true; return; }
    wrap.hidden = false;
    chips.innerHTML = "";
    selectedSlots.forEach(function (s) {
      var chip = document.createElement("div");
      chip.className = "slot-chip";
      var lbl = document.createElement("span");
      lbl.textContent = dateStrDisplay(s.date) + " · " + s.time;
      var removeBtn = document.createElement("button");
      removeBtn.type = "button";
      removeBtn.className = "chip-remove";
      removeBtn.setAttribute("aria-label", "Remove slot");
      removeBtn.textContent = "×";
      removeBtn.addEventListener("click", (function (ds, t, k) {
        return function () { toggleSlot(ds, t, k); };
      })(s.date, s.time, s.key));
      chip.appendChild(lbl);
      chip.appendChild(removeBtn);
      chips.appendChild(chip);
    });
  }

  document.getElementById("cal-prev").addEventListener("click", function () {
    calMonth--;
    if (calMonth < 0) { calMonth = 11; calYear--; }
    clearDayIfOutOfMonth();
    renderCalendar();
  });

  document.getElementById("cal-next").addEventListener("click", function () {
    calMonth++;
    if (calMonth > 11) { calMonth = 0; calYear++; }
    clearDayIfOutOfMonth();
    renderCalendar();
  });

  function clearDayIfOutOfMonth() {
    if (!selectedDay) return;
    var p = selectedDay.split("-");
    if (parseInt(p[0]) !== calYear || parseInt(p[1]) - 1 !== calMonth) {
      selectedDay = null;
      document.getElementById("cal-slots-wrap").hidden = true;
    }
  }

  // ── Validation ───────────────────────────────────────────────────────────
  function showError(id, message) {
    var el = document.getElementById(id);
    if (!el) return;
    el.textContent = message || "";
    if (el.previousElementSibling) {
      el.previousElementSibling.classList.toggle("has-error", !!message);
    }
  }

  function clearErrors() {
    form.querySelectorAll(".error-msg").forEach(function (el) { el.textContent = ""; });
    form.querySelectorAll(".has-error").forEach(function (el) { el.classList.remove("has-error"); });
    clearRegistrationError();
  }

  var REGISTRATION_FAILURE_MESSAGE =
    "We couldn't complete your registration right now. Please try again in a few minutes.";

  function showRegistrationError(message) {
    var el = document.getElementById("registration-error");
    if (!el) return;
    el.textContent = message || REGISTRATION_FAILURE_MESSAGE;
    el.hidden = false;
    el.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }

  function clearRegistrationError() {
    var el = document.getElementById("registration-error");
    if (!el) return;
    el.textContent = "";
    el.hidden = true;
  }

  function handleRegistrationFailure() {
    closeOtpModal();
    showRegistrationError(REGISTRATION_FAILURE_MESSAGE);
  }

  function updateSubmitState() {
    var ca = document.getElementById("confirm-accurate");
    var ap = document.getElementById("agree-privacy");
    var submitBtn = form.querySelector('button[type="submit"]');
    var ready = ca && ca.checked && ap && ap.checked && selectedSlots.length > 0;
    submitBtn.disabled = !ready;
  }

  function validate() {
    clearErrors();
    var valid = true;

    var fn  = document.getElementById("first-name");
    var ln  = document.getElementById("last-name");
    var dob = document.getElementById("dob");
    var ca  = document.getElementById("confirm-accurate");
    var ap  = document.getElementById("agree-privacy");

    if (!fn.value.trim())  { showError("err-first-name", "First name is required."); valid = false; }
    if (!ln.value.trim())  { showError("err-last-name",  "Last name is required.");  valid = false; }
    if (!dob.value.trim()) { showError("err-dob", "Date of birth is required."); valid = false; }
    else {
      var parsed = parseDob(dob.value.trim());
      if (!parsed) { showError("err-dob", "Please enter a valid date in MM/DD/YYYY format."); valid = false; }
      else if (parsed > new Date()) { showError("err-dob", "Date of birth cannot be in the future."); valid = false; }
    }
    if (!ca.checked) { showError("err-confirm-accurate", "Please confirm the information is accurate."); valid = false; }
    if (!ap.checked) { showError("err-agree-privacy",    "Please agree to the privacy policy / HIPAA notice."); valid = false; }

    if (!relationshipFields.hidden) {
      if (!relationshipSelect.value) { showError("err-relationship", "Please select your relationship to the patient."); valid = false; }
      if (relationshipSelect.value === "other" && !relationshipOtherInput.value.trim()) {
        showError("err-relationship-other", "Please specify the relationship."); valid = false;
      }
    }

    if (!valid) {
      var firstError = form.querySelector(".error-msg:not(:empty)");
      if (firstError) {
        firstError.scrollIntoView({ behavior: "smooth", block: "center" });
        var field = firstError.previousElementSibling;
        if (field && field.focus) field.focus();
      }
    }

    return valid;
  }

  // ── Submit + OTP verification ─────────────────────────────────────────────
  var pendingPayload = null;
  var sessionId = null;
  var verifiedMobileNumber = null;
  var otpTimerId = null;
  var successCloseTimerId = null;
  var successCloseIntervalId = null;
  var SUCCESS_AUTO_CLOSE_SECONDS = 5;
  var resendLocked = false;

  var otpModal = document.getElementById("otp-modal");
  var onfilePhoneInput = document.getElementById("onfile-phone");
  var mobileInput = document.getElementById("mobile-number");
  var otpInput = document.getElementById("otp-code");
  var otpSendBtn = document.getElementById("otp-send-btn");
  var otpVerifyBtn = document.getElementById("otp-verify-btn");
  var otpResendBtn = document.getElementById("otp-resend-btn");
  var otpCancelBtn = document.getElementById("otp-cancel-btn");
  var otpCancelProgress = document.getElementById("otp-cancel-progress");
  var otpCancelLabel = document.getElementById("otp-cancel-label");
  var otpCloseTimer = document.getElementById("otp-close-timer");
  var otpCloseSeconds = document.getElementById("otp-close-seconds");
  var otpStepSend = document.getElementById("otp-step-send");
  var otpStepVerify = document.getElementById("otp-step-verify");
  var otpStepSuccess = document.getElementById("otp-step-success");
  var otpModalTitle = document.getElementById("otp-modal-title");
  var otpModalSub = document.querySelector(".otp-modal-sub");
  var otpTimerWrap = document.getElementById("otp-timer");
  var otpTimerValue = document.getElementById("otp-timer-value");
  var otpPocHint = document.getElementById("otp-poc-hint");

  function authHeaders() {
    return {
      "Content-Type": "application/json",
      "X-Registration-Token": window.DELTAV_TOKEN || ""
    };
  }

  function showOtpError(id, message) {
    var el = document.getElementById(id);
    if (!el) return;
    el.textContent = message || "";
  }

  function normalizeMobileDigits(value) {
    var digits = (value || "").replace(/\D/g, "");
    if (digits.length === 11 && digits.charAt(0) === "1") {
      digits = digits.substring(1);
    }
    return digits.slice(0, 10);
  }

  function clearOtpErrors() {
    showOtpError("err-mobile", "");
    showOtpError("err-otp", "");
  }

  // Full on-file number (server-injected), normalized to 10 US digits. "" when absent.
  function getOnfilePhoneDigits() {
    var v = onfilePhoneInput ? (onfilePhoneInput.value || "").trim() : "";
    if (!v || v === "${phone_number}") return "";
    return normalizeMobileDigits(v);
  }

  function applyPhone() {
    if (!mobileInput) return;
    var digits = getOnfilePhoneDigits();
    if (digits.length === 10) {
      mobileInput.value = "(XXX) XXX-" + digits.slice(6);
      mobileInput.setAttribute("readonly", "readonly");
    } else {
      mobileInput.value = "";
      mobileInput.removeAttribute("readonly");
    }
  }

  function stopOtpTimer() {
    if (otpTimerId) {
      clearInterval(otpTimerId);
      otpTimerId = null;
    }
    if (otpTimerWrap) otpTimerWrap.hidden = true;
  }

  function stopSuccessCloseInterval() {
    if (successCloseIntervalId) {
      clearInterval(successCloseIntervalId);
      successCloseIntervalId = null;
    }
  }

  function stopSuccessCloseTimer() {
    if (successCloseTimerId) {
      clearTimeout(successCloseTimerId);
      successCloseTimerId = null;
    }
    stopSuccessCloseInterval();
  }

  var registrationFinished = false;

  function finishRegistrationSession() {
    if (registrationFinished) return;
    registrationFinished = true;
    stopSuccessCloseTimer();
    if (otpModal) {
      otpModal.hidden = true;
      otpModal.setAttribute("aria-hidden", "true");
    }
    var page = document.querySelector(".page");
    if (page) page.hidden = true;
    var done = document.getElementById("registration-done");
    if (done) done.hidden = false;
    document.title = "Registration complete";
  }

  function closeRegistrationTab() {
    if (registrationFinished) return;
    stopSuccessCloseTimer();
    window.close();
    setTimeout(finishRegistrationSession, 250);
  }

  function resetCloseCountdownUI() {
    if (!otpCancelBtn) return;
    otpCancelBtn.classList.remove("is-countdown");
    otpCancelBtn.style.removeProperty("--close-countdown");
    if (otpCancelProgress) {
      otpCancelProgress.hidden = true;
      otpCancelProgress.style.animation = "none";
      otpCancelProgress.style.width = "0";
      otpCancelProgress.onanimationend = null;
    }
    if (otpCancelLabel) otpCancelLabel.textContent = "Cancel";
    if (otpCloseTimer) otpCloseTimer.hidden = true;
    if (otpCloseSeconds) otpCloseSeconds.textContent = String(SUCCESS_AUTO_CLOSE_SECONDS);
  }

  function startCloseCountdownUI() {
    if (!otpCancelBtn) return;
    var remaining = SUCCESS_AUTO_CLOSE_SECONDS;

    otpCancelBtn.hidden = false;
    otpCancelBtn.classList.add("is-countdown");
    otpCancelBtn.style.setProperty("--close-countdown", SUCCESS_AUTO_CLOSE_SECONDS + "s");
    if (otpCancelLabel) otpCancelLabel.textContent = "Close";
    if (otpCloseTimer) otpCloseTimer.hidden = false;
    if (otpCloseSeconds) otpCloseSeconds.textContent = String(remaining);

    if (otpCancelProgress) {
      otpCancelProgress.hidden = false;
      otpCancelProgress.style.animation = "none";
      void otpCancelProgress.offsetWidth;
      otpCancelProgress.style.animation = "";
      otpCancelProgress.onanimationend = function () {
        closeRegistrationTab();
      };
    }

    stopSuccessCloseInterval();
    successCloseIntervalId = setInterval(function () {
      remaining -= 1;
      if (otpCloseSeconds) otpCloseSeconds.textContent = String(Math.max(0, remaining));
      if (remaining <= 0) stopSuccessCloseInterval();
    }, 1000);

    scheduleSuccessAutoClose();
  }

  function scheduleSuccessAutoClose() {
    stopSuccessCloseTimer();
    successCloseTimerId = setTimeout(closeRegistrationTab, SUCCESS_AUTO_CLOSE_SECONDS * 1000);
  }

  function setResendState(enabled, secondsRemaining) {
    if (!otpResendBtn) return;
    var canEnable = enabled && !resendLocked;
    otpResendBtn.disabled = !canEnable;
    if (!canEnable && secondsRemaining > 0) {
      otpResendBtn.textContent = "Resend OTP in " + secondsRemaining + "s";
    } else {
      otpResendBtn.textContent = "Resend OTP";
    }
  }

  function lockResend(seconds) {
    resendLocked = true;
    setResendState(false, seconds);
  }

  function unlockResend() {
    resendLocked = false;
    setResendState(true, 0);
  }

  function startOtpTimer(seconds) {
    stopOtpTimer();
    var remaining = seconds;
    lockResend(remaining);
    if (otpTimerValue) otpTimerValue.textContent = String(remaining);
    if (otpTimerWrap) otpTimerWrap.hidden = false;
    otpTimerId = setInterval(function () {
      remaining -= 1;
      if (otpTimerValue) otpTimerValue.textContent = String(Math.max(0, remaining));
      if (remaining > 0) {
        lockResend(remaining);
      } else {
        stopOtpTimer();
        unlockResend();
        showOtpError("err-otp", "OTP expired. Please resend a new code.");
      }
    }, 1000);
  }

  function resetOtpModalSteps() {
    if (otpModalTitle) otpModalTitle.hidden = false;
    if (otpModalSub) otpModalSub.hidden = false;
    if (otpStepSend) otpStepSend.hidden = false;
    if (otpStepVerify) otpStepVerify.hidden = true;
    if (otpStepSuccess) otpStepSuccess.hidden = true;
    if (otpCancelBtn) {
      otpCancelBtn.hidden = false;
      otpCancelBtn.classList.remove("is-countdown");
    }
    resetCloseCountdownUI();
  }

  function resolveOtpMobile() {
    var onfile = getOnfilePhoneDigits();
    return onfile.length === 10
      ? onfile
      : normalizeMobileDigits(mobileInput ? mobileInput.value : "");
  }

  function openOtpModal() {
    clearOtpErrors();
    stopOtpTimer();
    resendLocked = false;
    sessionId = null;
    verifiedMobileNumber = null;
    resetOtpModalSteps();
    applyPhone();
    if (otpResendBtn) {
      otpResendBtn.disabled = true;
      otpResendBtn.textContent = "Resend OTP";
    }
    if (otpInput) otpInput.value = "";
    if (otpPocHint) {
      otpPocHint.hidden = true;
      otpPocHint.textContent = "";
    }
    if (otpModal) {
      otpModal.hidden = false;
      otpModal.setAttribute("aria-hidden", "false");
    }
    if (mobileInput) mobileInput.focus();
  }

  function showRegistrationSuccess() {
    stopOtpTimer();
    if (otpModalTitle) otpModalTitle.hidden = true;
    if (otpModalSub) otpModalSub.hidden = true;
    if (otpStepSend) otpStepSend.hidden = true;
    if (otpStepVerify) otpStepVerify.hidden = true;
    if (otpStepSuccess) otpStepSuccess.hidden = false;
    if (otpModal) {
      otpModal.hidden = false;
      otpModal.setAttribute("aria-hidden", "false");
    }
    startCloseCountdownUI();
  }

  function closeOtpModal() {
    stopOtpTimer();
    stopSuccessCloseTimer();
    resetOtpModalSteps();
    if (otpModal) {
      otpModal.hidden = true;
      otpModal.setAttribute("aria-hidden", "true");
    }
  }

  function buildPayload() {
    var registrantChecked  = form.querySelector('input[name="registrant"]:checked');
    var patientTypeChecked = form.querySelector('input[name="patient_type"]:checked');
    var registrantValue    = registrantChecked  ? registrantChecked.value  : "myself";
    var patientTypeValue   = patientTypeChecked ? patientTypeChecked.value : "existing";

    var payload = {
      practice:         practiceId,
      registrant:       registrantValue,
      patient_type:     patientTypeValue,
      first_name:       document.getElementById("first-name").value.trim(),
      last_name:        document.getElementById("last-name").value.trim(),
      dob:              dobToIso(document.getElementById("dob").value.trim()),
      confirm_accurate: document.getElementById("confirm-accurate").checked,
      agree_privacy:    document.getElementById("agree-privacy").checked,
    };

    var middleName = document.getElementById("middle-name").value.trim();
    if (middleName) payload.middle_name = middleName;

    if (registrantValue === "another") {
      payload.relationship = relationshipSelect.value;
      if (relationshipSelect.value === "other") {
        payload.relationship_other = relationshipOtherInput.value.trim();
      }
    }

    if (selectedSlots.length > 0) {
      payload.preferred_slots = selectedSlots.map(function (s) { return s.date + " " + s.time; });
    }

    var comments = document.getElementById("comments").value.trim();
    if (comments) payload.comments = comments;

    if (window.DELTAV_TOKEN) {
      payload.registration_token = window.DELTAV_TOKEN;
    }

    return payload;
  }

  function submitRegistration(payload) {
    var submitBtn = form.querySelector('button[type="submit"]');
    submitBtn.disabled = true;
    submitBtn.textContent = "Submitting…";

    return fetch(API_BASE + "/api/register", {
      method: "POST",
      headers: authHeaders(),
      body: JSON.stringify(payload)
    })
      .then(function (res) {
        return res.json().then(function (data) { return { status: res.status, data: data }; });
      })
      .then(function (result) {
        if (result.status === 201) {
          clearRegistrationError();
          window.dataLayer = window.dataLayer || [];
          window.dataLayer.push({
            event: "booking_request",
            booking_details: { patient_type: payload.patient_type }
          });
          showRegistrationSuccess();
          form.reset();
          toggleFromRegistrant();
        } else {
          handleRegistrationFailure();
        }
      })
      .catch(function () {
        handleRegistrationFailure();
      })
      .finally(function () {
        if (otpStepSuccess && !otpStepSuccess.hidden) return;
        submitBtn.disabled = false;
        submitBtn.innerHTML = 'Continue <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M3 8h10M9 4l4 4-4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
      });
  }

  function sendOtp() {
    clearOtpErrors();
    var mobile = resolveOtpMobile();
    if (mobile.length !== 10) {
      showOtpError("err-mobile", "Please enter a valid 10-digit mobile number.");
      return;
    }

    var isResend = otpStepVerify && !otpStepVerify.hidden;
    if (isResend && otpResendBtn && otpResendBtn.disabled) {
      return;
    }

    var sendSucceeded = false;
    var payload = { practice: practiceId, mobile: mobile };
    if (isResend && sessionId) {
      payload = { session_id: sessionId };
    }

    if (otpResendBtn) otpResendBtn.disabled = true;
    if (isResend) {
      otpResendBtn.textContent = "Sending…";
    } else if (otpSendBtn) {
      otpSendBtn.disabled = true;
      otpSendBtn.textContent = "Sending…";
    }

    fetch((API_BASE || window.location.origin) + "/api/otp/send", {
      method: "POST",
      headers: authHeaders(),
      body: JSON.stringify(payload)
    })
      .then(function (res) {
        return res.json().then(function (data) { return { status: res.status, data: data }; });
      })
      .then(function (result) {
        if (result.status === 429 && result.data.error === "resend_throttled") {
          showOtpError("err-otp", "Please wait " + (result.data.retry_after_seconds || 60) + "s before resending.");
          return;
        }
        if (result.status === 429 && result.data.error === "send_limit_reached") {
          showOtpError("err-otp", "Maximum resend limit reached. Please refresh and try again.");
          return;
        }
        if (result.status !== 200 || !result.data.success) {
          showOtpError(isResend ? "err-otp" : "err-mobile",
            result.data.error === "invalid_practice"
              ? "Invalid practice. Please use the registration link provided by your practice."
              : result.data.error === "invalid_mobile"
              ? "Please enter a valid mobile number."
              : result.data.error === "sms_send_failed"
              ? (result.data.message || "Could not send OTP. Please try again.")
              : "Could not send OTP. Please try again.");
          return;
        }

        sessionId = result.data.session_id;
        verifiedMobileNumber = mobile;
        if (otpStepSend) otpStepSend.hidden = true;
        if (otpStepVerify) otpStepVerify.hidden = false;
        if (otpInput) {
          otpInput.value = "";
          otpInput.focus();
        }
        sendSucceeded = true;
        showOtpError("err-otp", "");
        startOtpTimer(result.data.expires_in_seconds || 60);
        if (result.data.poc_otp && otpPocHint) {
          otpPocHint.hidden = false;
          otpPocHint.textContent = "Development — your code is " + result.data.poc_otp;
        }
      })
      .catch(function () {
        showOtpError(isResend ? "err-otp" : "err-mobile", "Network error. Please try again.");
      })
      .finally(function () {
        if (isResend) {
          if (!sendSucceeded && !otpTimerId) unlockResend();
        } else if (otpSendBtn) {
          otpSendBtn.disabled = false;
          otpSendBtn.textContent = "Send OTP";
        }
        if (otpResendBtn) otpResendBtn.textContent = "Resend OTP";
      });
  }

  function verifyOtp() {
    clearOtpErrors();
    var otp = (otpInput ? otpInput.value : "").trim();

    if (!sessionId) {
      showOtpError("err-otp", "Session expired. Please start again.");
      return;
    }
    if (!/^\d{6}$/.test(otp)) {
      showOtpError("err-otp", "Please enter the 6-digit OTP.");
      return;
    }

    otpVerifyBtn.disabled = true;
    otpVerifyBtn.textContent = "Verifying…";

    fetch((API_BASE || window.location.origin) + "/api/otp/verify", {
      method: "POST",
      headers: authHeaders(),
      body: JSON.stringify({ session_id: sessionId, otp: otp })
    })
      .then(function (res) {
        return res.json().then(function (data) { return { status: res.status, data: data }; });
      })
      .then(function (result) {
        if (result.data.refresh_required || result.data.error === "too_many_attempts") {
          alert("Too many incorrect attempts. The page will refresh.");
          window.location.reload();
          return;
        }
        if (result.data.verified) {
          stopOtpTimer();
          if (pendingPayload) {
            pendingPayload.session_id = sessionId;
            pendingPayload.mobile_number = verifiedMobileNumber || resolveOtpMobile();
            return submitRegistration(pendingPayload);
          }
          return;
        }
        if (result.data.error === "otp_expired") {
          showOtpError("err-otp", "OTP expired. Please resend a new code.");
          return;
        }
        var remaining = result.data.attempts_remaining;
        showOtpError("err-otp", typeof remaining === "number"
          ? "Incorrect OTP. " + remaining + " attempt(s) remaining."
          : "Incorrect OTP. Please try again.");
      })
      .catch(function () {
        showOtpError("err-otp", "Network error. Please try again.");
      })
      .finally(function () {
        otpVerifyBtn.disabled = false;
        otpVerifyBtn.textContent = "Verify & Continue";
      });
  }

  function handleOtpCancelOrClose() {
    if (otpStepSuccess && !otpStepSuccess.hidden) {
      closeRegistrationTab();
      return;
    }
    closeOtpModal();
  }

  if (otpSendBtn) otpSendBtn.addEventListener("click", sendOtp);
  if (otpResendBtn) otpResendBtn.addEventListener("click", sendOtp);
  if (otpVerifyBtn) otpVerifyBtn.addEventListener("click", verifyOtp);
  if (otpCancelBtn) otpCancelBtn.addEventListener("click", handleOtpCancelOrClose);

  if (mobileInput) {
    mobileInput.addEventListener("input", function () {
      var digits = normalizeMobileDigits(mobileInput.value);
      if (digits.length <= 3) {
        mobileInput.value = digits;
      } else if (digits.length <= 6) {
        mobileInput.value = "(" + digits.slice(0, 3) + ") " + digits.slice(3);
      } else {
        mobileInput.value = "(" + digits.slice(0, 3) + ") " + digits.slice(3, 6) + "-" + digits.slice(6);
      }
    });
  }

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    if (!validate()) return;
    pendingPayload = buildPayload();
    openOtpModal();
  });

  document.getElementById("confirm-accurate").addEventListener("change", updateSubmitState);
  document.getElementById("agree-privacy").addEventListener("change", updateSubmitState);

  // ── Init ──────────────────────────────────────────────────────────────────
  nextInput.value = getNextUrl();
  prefillFromParams();
  toggleFromRegistrant();

  var now = new Date();
  calYear  = now.getFullYear();
  calMonth = now.getMonth();
  selectedDay = null;
  renderCalendar();
  updateSubmitState();

})();
