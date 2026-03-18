(function () {
  "use strict";

  var form = document.getElementById("registration-form");
  var nextInput = document.getElementById("next-url");
  var relationshipFields = document.getElementById("relationship-fields");
  var relationshipSelect = document.getElementById("relationship");
  var relationshipOtherWrap = document.getElementById("relationship-other-wrap");
  var relationshipOtherInput = document.getElementById("relationship-other");

  var radios = form.querySelectorAll('input[name="registrant"]');

  // Allowed redirect hosts (add your partners or use * for POC)
  var ALLOWED_HOSTS = [
    "localhost",
    "127.0.0.1",
    "leadsmanagementweb.revenuewell.com",
    window.location.host,
  ];

  function getNextUrl() {
    var params = new URLSearchParams(window.location.search);
    var next = params.get("next") || params.get("redirect") || "";
    try {
      return next ? decodeURIComponent(next) : "";
    } catch (e) {
      return "";
    }
  }

  function isAllowedRedirect(url) {
    if (!url) return false;
    try {
      var u = new URL(url);
      return ALLOWED_HOSTS.indexOf(u.hostname) !== -1 || ALLOWED_HOSTS.indexOf("*") !== -1;
    } catch (e) {
      return false;
    }
  }

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
    var isAnother = form.querySelector('input[name="registrant"]:checked').value === "another";
    showRelationshipFields(isAnother);
  }

  // Bind: who is this for
  radios.forEach(function (r) {
    r.addEventListener("change", toggleFromRegistrant);
  });

  relationshipSelect.addEventListener("change", toggleRelationshipOther);

  // Read practice ID from URL
  var practiceId = new URLSearchParams(window.location.search).get("practice") || "";

  // Initialize next URL and conditional state
  nextInput.value = getNextUrl();
  toggleFromRegistrant();

  // Auto-format DOB input as MM/DD/YYYY
  var dobInput = document.getElementById("dob");
  if (dobInput) {
    dobInput.addEventListener("input", function (e) {
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
    var mm = parseInt(parts[0], 10);
    var dd = parseInt(parts[1], 10);
    var yyyy = parseInt(parts[2], 10);
    if (!mm || !dd || !yyyy || mm < 1 || mm > 12 || dd < 1 || dd > 31 || yyyy < 1900) return null;
    var date = new Date(yyyy, mm - 1, dd);
    if (date.getMonth() !== mm - 1 || date.getDate() !== dd) return null;
    return date;
  }

  function dobToIso(str) {
    var parts = str.split("/");
    return parts[2] + "-" + parts[0] + "-" + parts[1];
  }

  function showError(id, message) {
    var el = document.getElementById(id);
    if (el) {
      el.textContent = message || "";
      el.previousElementSibling && el.previousElementSibling.classList.toggle("has-error", !!message);
    }
  }

  function clearErrors() {
    form.querySelectorAll(".error-msg").forEach(function (el) {
      el.textContent = "";
    });
    form.querySelectorAll(".has-error").forEach(function (el) {
      el.classList.remove("has-error");
    });
  }

  function validate() {
    clearErrors();
    var valid = true;

    var firstName = document.getElementById("first-name");
    var lastName = document.getElementById("last-name");
    var dob = document.getElementById("dob");
    var confirmAccurate = document.getElementById("confirm-accurate");
    var agreePrivacy = document.getElementById("agree-privacy");

    if (!firstName.value.trim()) {
      showError("err-first-name", "First name is required.");
      valid = false;
    }
    if (!lastName.value.trim()) {
      showError("err-last-name", "Last name is required.");
      valid = false;
    }
    if (!dob.value.trim()) {
      showError("err-dob", "Date of birth is required.");
      valid = false;
    } else {
      var parsed = parseDob(dob.value.trim());
      if (!parsed) {
        showError("err-dob", "Please enter a valid date in MM/DD/YYYY format.");
        valid = false;
      } else if (parsed > new Date()) {
        showError("err-dob", "Date of birth cannot be in the future.");
        valid = false;
      }
    }

    if (!confirmAccurate.checked) {
      showError("err-confirm-accurate", "Please confirm the information is accurate.");
      valid = false;
    }
    if (!agreePrivacy.checked) {
      showError("err-agree-privacy", "Please agree to the privacy policy / HIPAA notice.");
      valid = false;
    }

    if (!relationshipFields.hidden) {
      if (!relationshipSelect.value) {
        showError("err-relationship", "Please select relationship to patient.");
        valid = false;
      }
      if (relationshipSelect.value === "other" && !relationshipOtherInput.value.trim()) {
        showError("err-relationship-other", "Please specify the relationship.");
        valid = false;
      }
    }

    return valid;
  }

  // Set this to your Azure Functions URL in production, e.g. "https://deltav-api.azurewebsites.net"
  var API_BASE = window.DELTAV_API_URL || "";

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    if (!validate()) return;

    var registrantValue = form.querySelector('input[name="registrant"]:checked').value;
    var patientTypeValue = form.querySelector('input[name="patient_type"]:checked').value;
    var payload = {
      practice: practiceId,
      registrant: registrantValue,
      patient_type: patientTypeValue,
      first_name: document.getElementById("first-name").value.trim(),
      last_name: document.getElementById("last-name").value.trim(),
      dob: dobToIso(document.getElementById("dob").value.trim()),
      confirm_accurate: document.getElementById("confirm-accurate").checked,
      agree_privacy: document.getElementById("agree-privacy").checked
    };

    if (registrantValue === "another") {
      payload.relationship = relationshipSelect.value;
      if (relationshipSelect.value === "other") {
        payload.relationship_other = relationshipOtherInput.value.trim();
      }
    }

    var submitBtn = form.querySelector('button[type="submit"]');
    submitBtn.disabled = true;
    submitBtn.textContent = "Submitting...";

    // Attach short-lived token if present
    if (window.DELTAV_TOKEN) {
      payload.registration_token = window.DELTAV_TOKEN;
    }

    fetch(API_BASE + "/api/register", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Registration-Token": (window.DELTAV_TOKEN || "") },
      body: JSON.stringify(payload)
    })
        .then(function (res) {
          return res.json().then(function (data) {
            return { status: res.status, data: data };
          });
        })
        .then(function (result) {
          if (result.status === 201) {
            var redirect = result.data.redirect_url;
            if (redirect) {
              window.location.href = redirect;
            } else {
              alert("Registration submitted successfully!");
              form.reset();
              toggleFromRegistrant();
            }
          } else if (result.status === 400 && result.data.errors) {
            alert("Validation errors:\n" + result.data.errors.join("\n"));
          } else {
            alert("Something went wrong. Please try again.");
          }
        })
        .catch(function () {
          alert("Network error. Please check your connection and try again.");
        })
        .finally(function () {
          submitBtn.disabled = false;
          submitBtn.textContent = "Continue";
        });
  });
})();
