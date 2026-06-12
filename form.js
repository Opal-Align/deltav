(function () {
  "use strict";

  var form = document.getElementById("registration-form");
  var nextInput = document.getElementById("next-url");
  var registrantSection = document.getElementById("registrant-section");
  var relationshipFields = document.getElementById("relationship-fields");
  var relationshipSelect = document.getElementById("relationship");
  var relationshipOtherWrap = document.getElementById("relationship-other-wrap");
  var relationshipOtherInput = document.getElementById("relationship-other");
  var radios = form.querySelectorAll('input[name="registrant"]');

  var API_URL = "https://deltavapi-dev-hwa6f6a0dag0fmh3.eastus-01.azurewebsites.net/api/register";

  var ALLOWED_HOSTS = [
    "localhost",
    "127.0.0.1",
    "leadsmanagementweb.revenuewell.com",
    window.location.host,
  ];

  // ── URL helpers ──────────────────────────────────────────────────────────
  function getNextUrl() {
    var params = new URLSearchParams(window.location.search);
    var next = params.get("next") || params.get("redirect") || "";
    try { return next ? decodeURIComponent(next) : ""; } catch (e) { return ""; }
  }

  function isAllowedRedirect(url) {
    if (!url) return false;
    try {
      var u = new URL(url);
      return ALLOWED_HOSTS.indexOf(u.hostname) !== -1 || ALLOWED_HOSTS.indexOf("*") !== -1;
    } catch (e) { return false; }
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
    var email      = get(["email"]);
    var phone      = get(["phone", "phone_number"]);

    function fill(id, val) {
      if (!val) return;
      var el = document.getElementById(id);
      el.value = val;
      el.classList.add("prefilled");
    }

    fill("first-name",  firstName);
    fill("middle-name", middleName);
    fill("last-name",   lastName);
    fill("dob",         dob);
    fill("email",       email);
    fill("phone",       phone);

    // If patient info is provided via URL → existing patient, hide the registrant toggle
    if (firstName && lastName) {
      registrantSection.hidden = true;
      document.getElementById("prefill-notice").hidden = false;
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

  // ── Calendar & slot picker ────────────────────────────────────────────────
  var calYear, calMonth, selectedDay;
  var selectedSlots = [];
  var MAX_SLOTS = 3;

  var MONTH_NAMES = ["January","February","March","April","May","June","July","August","September","October","November","December"];
  var DAY_ABBR    = ["Su","Mo","Tu","We","Th","Fr","Sa"];
  var DAY_SHORT   = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
  var MON_SHORT   = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];

  var TIME_SLOTS = [
    "9:00 AM","9:30 AM","10:00 AM","10:30 AM","11:00 AM","11:30 AM",
    "12:00 PM","12:30 PM",
    "1:00 PM","1:30 PM","2:00 PM","2:30 PM","3:00 PM","3:30 PM","4:00 PM","4:30 PM","5:00 PM"
  ];

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

      if (cellDate < today) {
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

      if (!isSelected && atMax) {
        btn.disabled = true;
        btn.classList.add("disabled");
      }

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
      var label = document.createElement("span");
      label.textContent = dateStrDisplay(s.date) + " · " + s.time;
      var removeBtn = document.createElement("button");
      removeBtn.type = "button";
      removeBtn.className = "chip-remove";
      removeBtn.setAttribute("aria-label", "Remove " + s.time + " on " + s.date);
      removeBtn.textContent = "×";
      removeBtn.addEventListener("click", (function (ds, t, k) {
        return function () { toggleSlot(ds, t, k); };
      })(s.date, s.time, s.key));
      chip.appendChild(label);
      chip.appendChild(removeBtn);
      chips.appendChild(chip);
    });
  }

  document.getElementById("cal-prev").addEventListener("click", function () {
    calMonth--;
    if (calMonth < 0) { calMonth = 11; calYear--; }
    clearDaySelectionIfOutOfMonth();
    renderCalendar();
  });

  document.getElementById("cal-next").addEventListener("click", function () {
    calMonth++;
    if (calMonth > 11) { calMonth = 0; calYear++; }
    clearDaySelectionIfOutOfMonth();
    renderCalendar();
  });

  function clearDaySelectionIfOutOfMonth() {
    if (!selectedDay) return;
    var p = selectedDay.split("-");
    if (parseInt(p[0]) !== calYear || parseInt(p[1]) - 1 !== calMonth) {
      selectedDay = null;
      document.getElementById("cal-slots-wrap").hidden = true;
    }
  }

  function initCalendar() {
    var now = new Date();
    calYear  = now.getFullYear();
    calMonth = now.getMonth();
    selectedDay = null;
    renderCalendar();
  }

  // ── Date of birth: block future dates ────────────────────────────────────
  var dobInput = document.getElementById("dob");
  if (dobInput) {
    var t = new Date();
    dobInput.setAttribute("max", t.getFullYear() + "-" + padZ(t.getMonth() + 1) + "-" + padZ(t.getDate()));
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
  }

  function validate() {
    clearErrors();
    var valid = true;
    var emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

    var fn   = document.getElementById("first-name");
    var ln   = document.getElementById("last-name");
    var dob  = document.getElementById("dob");
    var ph   = document.getElementById("phone");
    var em   = document.getElementById("email");
    var ca   = document.getElementById("confirm-accurate");
    var ap   = document.getElementById("agree-privacy");

    if (!fn.value.trim())               { showError("err-first-name", "First name is required."); valid = false; }
    if (!ln.value.trim())               { showError("err-last-name",  "Last name is required.");  valid = false; }
    if (!dob.value)                     { showError("err-dob",        "Date of birth is required."); valid = false; }
    else if (new Date(dob.value) > new Date()) { showError("err-dob", "Date of birth cannot be in the future."); valid = false; }
    if (!ph.value.trim())               { showError("err-phone",      "Phone number is required."); valid = false; }
    if (!em.value.trim())               { showError("err-email",      "Email is required."); valid = false; }
    else if (!emailRegex.test(em.value.trim())) { showError("err-email", "Please enter a valid email address."); valid = false; }
    if (!ca.checked)                    { showError("err-confirm-accurate", "Please confirm the information is accurate."); valid = false; }
    if (!ap.checked)                    { showError("err-agree-privacy",    "Please agree to the privacy policy / HIPAA notice."); valid = false; }

    if (!relationshipFields.hidden) {
      if (!relationshipSelect.value) { showError("err-relationship", "Please select your relationship to the patient."); valid = false; }
      if (relationshipSelect.value === "other" && !relationshipOtherInput.value.trim()) {
        showError("err-relationship-other", "Please specify the relationship."); valid = false;
      }
    }

    return valid;
  }

  // ── Payload builder ───────────────────────────────────────────────────────
  function buildPayload() {
    var checked    = form.querySelector('input[name="registrant"]:checked');
    var registrant = checked ? checked.value : "myself";

    var payload = {
      registrant:     registrant,
      first_name:     document.getElementById("first-name").value.trim(),
      middle_name:    document.getElementById("middle-name").value.trim() || undefined,
      last_name:      document.getElementById("last-name").value.trim(),
      dob:            document.getElementById("dob").value,
      phone:          document.getElementById("phone").value.trim(),
      email:          document.getElementById("email").value.trim(),
      confirm_accurate: document.getElementById("confirm-accurate").checked,
      agree_privacy:    document.getElementById("agree-privacy").checked,
    };

    if (registrant === "another") {
      payload.relationship = relationshipSelect.value;
      if (relationshipSelect.value === "other") {
        payload.relationship_other = relationshipOtherInput.value.trim() || undefined;
      }
    }

    if (selectedSlots.length > 0) {
      payload.preferred_slots = selectedSlots.map(function (s) { return s.date + " " + s.time; });
    }

    var next = nextInput.value.trim();
    if (next) payload.redirect_url = next;

    return payload;
  }

  // ── Submit ────────────────────────────────────────────────────────────────
  form.addEventListener("submit", function (e) {
    e.preventDefault();
    if (!validate()) return;

    var next    = nextInput.value.trim();
    var payload = buildPayload();

    function doRedirect(redirectUrl) {
      var url = (redirectUrl && redirectUrl.trim()) || next;
      if (url && isAllowedRedirect(url)) {
        window.location.href = url;
      } else if (url) {
        alert("Redirect is not allowed to that URL.");
      } else {
        alert("Registration received.");
      }
    }

    if (API_URL) {
      var btn = form.querySelector('button[type="submit"]');
      btn.disabled = true;
      btn.textContent = "Sending…";

      fetch(API_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      })
        .then(function (res) {
          if (!res.ok) throw new Error("Request failed: " + res.status);
          var ct = res.headers.get("Content-Type") || "";
          if (ct.indexOf("application/json") !== -1) {
            return res.json().then(function (data) {
              doRedirect((data && (data.redirect_url || data.redirectUrl || data.redirect || data.url)) || "");
            });
          }
          doRedirect();
        })
        .catch(function (err) {
          btn.disabled = false;
          btn.innerHTML = 'Continue <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M3 8h10M9 4l4 4-4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
          alert("Something went wrong. Please try again.");
          console.error(err);
        });
    } else {
      doRedirect();
    }
  });

  // ── Init ──────────────────────────────────────────────────────────────────
  nextInput.value = getNextUrl();
  prefillFromParams();
  toggleFromRegistrant();
  initCalendar();

})();
